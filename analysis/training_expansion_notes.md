# 第 5 章训练方法扩充：工程依据与核验记录

## 写作角度

- `作业1.5_PyTorch_NLL/report.md` 将批次均值、序列标签对齐和忽略位置放回损失定义中解释；本扩充稿沿用“先定义目标位置，再说明归约”的分析顺序，不引用其数据、实现或措辞。
- `作业3.1_BERT标点恢复/report.md` 对输入位置、特殊符号和不参与损失的位置作显式区分。本文的因果语言模型使用响应词元掩码，与其逐字符分类掩码不同，故这里只借鉴说明标签对齐和排除位置的分析角度。
- `作业5.2_后训练代码分析与实验/实验报告.md` 以实际数据流、张量目标、损失分母和运行资源解释优化过程。扩充稿据此将模型目标、TRL 数据整理器和 ZeRO-3 配置对应起来，不复用其算法讨论或文字表述。

## 训练序列与标签

依据文件：

- `materials/training/sft_aix_base_spm_comment_validation.py`
- `materials/training/trl/trainer/sft_trainer.py`
- `materials/training/trl/trainer/sft_config.py`
- `materials/training/trl/trainer/utils.py`
- `analysis/training_notes.md`

训练入口的格式函数从记录读取 `prompt`，优先读取 `output`、不存在时读取 `source`，并拼接 `</s>`。记录的提示结构包含全局声明、相似代码片段、目标骨架、文件级任务标记、目标路径、语言和细化需求；已核查的 v4 记录每条只有一个目标骨架片段。提示最终使用响应标记 `The complete source code begins below:\n`。Java 与 Python 需求采用各自语言的注释前缀；输入整理函数没有去除源码注释的处理。

`DataCollatorForCompletionOnlyLM` 的 `instruction_template` 为 `None`，因此按响应标记定位标签边界。其父类先将输入词元复制为标签；定制逻辑随后把从序列开头至响应标记结束的标签设置为 `ignore_index=-100`。标记后源码和结束词元仍有效。未找到响应标记时，定制 collator 会把整条序列标签置为忽略并产生 warning；本轮只确认训练日志中未检出该 warning 字符串，不据此宣称一定没有样本被忽略。启用 `padding_free` 后，collator 去除注意力掩码标示的填充位置、生成位置编号，并将位置编号为 0 的标签屏蔽。

SFTTrainer 在非打包路径中对格式化文本调用 tokenizer，参数为 `truncation=True`、`padding=False`、`max_length=32768`。`SFTConfig` 的 `packing` 默认值为 `False`，命令行未覆盖，保存的训练参数也记录为 `False`；该路径不会将多个记录拼接成固定长度块。每卡批大小为 1，故 `padding_free=True` 没有多样本补齐可压缩，不能与 packing 混称。模型配置列出 `model_type=llama`、`architectures=LlamaForCausalLM`、`bos_token_id=1`、`eos_token_id=2`。格式函数显式附加的 EOS 字符串是 `</s>`；脚本调用 tokenizer 时使用其默认 `add_special_tokens=True`，没有手动拼接 BOS 文本。

归档记录只有字符长度分布，没有逐条 tokenizer 长度、截断位点或截断计数。因此正文可以说明上限与截断行为，不能写成序列均未截断，也不能给出截断样本比例。TRL 与 Transformers 版本中 collator 对 marker 缺失的处理是代码路径证据；运行中是否发生该分支仅能以日志为限。

## 损失归约与全参数更新

依据文件：

- `materials/training/commands_sft_aixcoder.sh`
- `materials/training/deepspeed_zero3.yaml`
- `materials/training/trl/trainer/model_config.py`
- `materials/training/trl/trainer/sft_trainer.py`
- `materials/training/trl/trainer/utils.py`
- `materials/training/final_model_config.json`
- `materials/training/training_args.bin`
- `analysis/training_notes.md`

训练参数归档为 PyTorch ZIP 序列化文件。本轮仅用 Python 标准库 `zipfile` 读取归档内 pickle 字节、以 `pickletools` 检查静态操作码；没有反序列化对象或执行工程代码。保存参数记录：

| 参数 | 归档值 |
|---|---:|
| `per_device_train_batch_size` | 1 |
| `gradient_accumulation_steps` | 1 |
| `num_train_epochs` | 10 |
| `learning_rate` | \(5\times10^{-6}\) |
| `weight_decay` | 0.01 |
| `max_grad_norm` | 1.0 |
| `warmup_ratio` | 0.05 |
| `lr_scheduler_type` | `linear` |
| `optim` | `adamw_torch` |
| `bf16` / `bf16_full_eval` | true / true |
| `max_seq_length` | 32,768 |
| `packing` | false |

`training_args.bin` 中没有 `average_tokens_across_devices` 字段；TRL 的定制 SFTTrainer 没有覆写 Trainer 的 `compute_loss`，模型配置指向默认因果语言模型。扩充稿按常规 Llama 因果语言模型语义，将模型 loss 描述为对非 `-100` 有效标签的词元交叉熵均值，并说明数据并行同步梯度。未将其描述为跨设备总有效词元数池化后的全局单次均值。Transformer 训练运行信息显示版本为 `4.46.0.dev0`；此字段来自保存的模型配置。损失公式表达目标词元层面的条件概率，正文同时指出它与文件测试通过率的统计对象不同。

全参数判断由以下证据共同支持：实际命令未传入 `--use_peft`、LoRA 或 4/8 位加载参数；`ModelConfig.use_peft` 和 `load_in_4bit` 默认均为 false，`get_peft_config` 在 `use_peft=False` 时返回空值；训练参数和输出配置为 BF16 全模型而非低比特适配器。ZeRO 配置为 stage 3、8 进程、无 CPU/NVMe offload，并设置 16 位模型保存。配置支持描述参数、梯度和优化器状态分片；没有依据声称 GPU 型号或某个 GPU 型号造成了这次训练时长。

## 梯度记录的描述性统计

分析输入：

- 原始数据：`analysis/training_step_metrics.csv`，包含 2,280 步的 `train_loss`、`learning_rate`、`grad_norm`。
- 派生数据：`analysis/training_gradient_diagnostics.csv`，由 `analysis/plot_training_diagnostics.py` 生成；每行保留原记录并增加尾随 50 步中位数和预热/衰减阶段标签。
- 图：`figures/training_gradient_diagnostics.png` 与 `.svg`。主报告图 6 使用对数纵轴呈现 `grad_norm`，并同图列出学习率；不重画 train/eval loss。

逐步 `grad_norm` 记录的全程中位数为 0.9851，95 分位数为 3.9998，最大值为 99.1736（第 546 步）。配置中的梯度范数阈值为 1.0，但现有归档不能确认 Trainer/DeepSpeed 日志字段是在裁剪前还是裁剪后采样；因此不能把日志值大于 1 的比例写成裁剪触发比例。

基于 `warmup_ratio=0.05` 和 2,280 个实际优化步，预热区间为前 114 步；该段记录范数中位数为 4.2237，后续 2,166 步为 0.9022，最后一轮 228 步为 0.0149。原逐步损失同时整体下降。整个轨迹的 Pearson 相关系数为：`grad_norm` 与学习率约 0.313、与训练损失约 0.489。训练步数、损失和学习率同时变化且序列具有趋势，相关系数只描述该轨迹上的共变，不用于归因预热或衰减造成范数变化。第 546 步的局部峰值没有阻止训练继续到第 2,280 步；无需据此推断训练稳定性或梯度裁剪的内部状态。

功能评测的 0826 历史预测权重仍没有检查点或 epoch 映射。此诊断图只描述 V4 的训练事件，不应和 0826 预测或其 220 个文件的功能评测结果归为同一模型轨迹。
