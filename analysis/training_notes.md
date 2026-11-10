# SFT 训练核查记录

## 训练身份与实现

本次训练运行名为 7b_v2_sft_v4_0905，训练工程为 aixcoder-7b-v2。启动脚本指定基础模型 aiXcoder/aixcoder-7b-v2，缓存 revision 为 94a44b5caba59512e26da3335e1b8bdd07ffc5fa；输出目录为 models/7b_v2_sft_v4_0905。导出的模型配置为 Llama 架构：词表 49,152，最大位置长度 65,536，32 层，隐藏维度 4,096，前馈维度 14,464，32 个 query heads 和 8 个 key/value heads。训练的最大序列长度设为 32,768。

启动命令没有启用 use_peft、LoRA 或量化选项；脚本把 get_peft_config(model_config) 传给 SFTTrainer，但生成的训练参数中未启用 PEFT，保存目录包含完整模型权重分片。该次实验因此是全参数监督微调。序列化训练参数 training_args.bin 的可读字符串字段给出优化器 adamw_torch 和学习率调度器 linear；该文件未反序列化执行。

## 数据与训练目标

训练文件 data/v4/train.jsonl 有 1,822 条记录，验证文件 data/v4/test.jsonl 有 452 条。训练集含 Java 984 条、Python 838 条；验证集含 Java 242 条、Python 210 条。训练脚本分别加载两个文件，并在加载后以 seed 42 打乱；它没有在训练脚本内随机切分数据。尽管文件名是 test.jsonl，该文件作为 eval_dataset 在每个 epoch 后评估，因此在本实验中是用于训练期间监控的验证集，没有证据表明另有训练后独立测试集。脚本没有按 only_sft_train 过滤：训练集中的 703 条 true 和 1,119 条 false 记录均进入训练；验证集 452 条均为 false。

v4 JSONL 字段包括 id、skeleton、detailed_requirement、prompt、source、namespace、language 和 only_sft_train。源代码字段长度按字符计：训练集最小 252、中位数 3,420、P90 为 15,980、最大 110,863；验证集最小 409、中位数 5,410、P90 为 23,926、最大 87,914。未运行 tokenizer，因此这些数值不代表 token 长度。字段和长度分布以及逐样本 ID、prompt 中可见路径、源码 SHA-256 汇总见 ../materials/training/data/v4_sample_index.csv；Java 的目标路径通过 ID 回连 ../materials/data_construction/analysis/sft_source_index.csv。

通过样本 ID 与数据构造索引回连，v4 训练集的 984 条 Java 记录全部与原 sft/train 的 ID 和目标源码 SHA-256 一致；v4 验证集 242 条 Java 记录全部与原 sft/eval 的 ID 和目标源码 SHA-256 一致。Python 文件路径从 prompt 的目标路径行提取，Java 路径通过同一 ID 回连原始索引。完成路径映射后，两集合间没有 ID、仓库+路径、源码精确 SHA-256 或换行归一化 SHA-256 重叠；它们仍共享 29 个仓库，因此这是样本/文件级隔离，不是仓库级隔离。训练集内另有一个完全相同源码哈希，ID 为 inventree--check_migrations--73aa40d6fc1611e2 与 inventree--runmigrations--6e322f8ef793a6a9；验证集没有该源码哈希。

当前 220 条离线预测样本均属于 v4 验证集的 Java 子集，目标源码 SHA-256 也逐条一致；不过其记录的推理模型是 aixcoder-7b-v2-sft-0826，并非本次 7b_v2_sft_v4_0905。因此这些预测结果不能作为 v4 checkpoint 的生成评测结果。v4 训练本身可核验的评估证据是每轮 452 条验证数据上的 loss。

## v4 实际 prompt 与目标格式

对 2,274 条训练/验证记录检查后，每条 prompt 都包含响应标记 The complete source code begins below:，并恰有一个 skeleton 1 片段。Python 样本以 # 注释前缀，Java 样本以 // 注释前缀。prompt 结构为：Global Declarations、Similar Code Snippets 与单个骨架片段、Full-file Completion Task、AIX-SPAN 标记、目标文件路径、语言标签、细化自然语言需求，最后是完成源码的响应标记。这里使用的是记录中的单数 skeleton 字段和一个目标文件骨架片段；数据格式没有 skeletons 依赖闭包字段，训练脚本也不在读取时展开依赖骨架闭包。

训练格式函数把 prompt 与 source 字段直接拼接，再追加 </s>；没有 output 字段时使用 source。DataCollatorForCompletionOnlyLM 以 The complete source code begins below: 加换行作为 response template，只对标记后的完成部分计算语言模型损失。训练脚本没有去注释或清洗 source 的步骤；训练集 Java 984 条中有 977 条含注释行，Python 838 条中有 699 条含注释行，验证集 Java 242 条中有 242 条含注释行，Python 210 条中有 184 条含注释行。

## 超参数与计算设置

| 项目 | 实际设置 |
|---|---:|
| 训练轮数 / 每轮更新步数 / 总更新步数 | 10 / 228 / 2,280 |
| 每设备训练批量 / 梯度累积 | 1 / 1 |
| 单机进程数 / nominal global batch | 8 / 8 |
| 最大序列长度 | 32,768 |
| 学习率 / warmup ratio / warmup steps | 5×10⁻⁶ / 0.05 / 114 |
| 优化器 / 学习率调度 | adamw_torch / linear |
| 权重衰减 / 最大梯度范数 | 0.01 / 1.0 |
| 精度 | bf16 训练及 bf16 full evaluation |
| 注意力 / 其他内存优化 | FlashAttention 2、gradient checkpointing、Liger kernel |
| 分布式训练 | DeepSpeed ZeRO stage 3，单机 8 进程 |
| 保存 / 评估间隔 | 每 epoch 保存 / 每 epoch 评估 |

训练日志记录 2,280 步、训练总运行时间 14,463.4404 秒（约 4:01:03）、Trainer 汇总 train_loss=0.0323303379，并在每轮对 452 条评估集记录 57 个评估步骤。TensorBoard 事件文件为 489,322 字节，包含 2,280 个逐步训练损失标量和 10 个逐轮验证损失标量。每轮平均训练损失是该轮 228 个 train/loss 事件值的算术平均；它与 Trainer 汇总训练损失的统计口径不同。

| Epoch | Step | 每轮逐步训练 loss 均值 | 验证 loss |
|---:|---:|---:|---:|
| 1 | 228 | 0.174154 | 0.159281 |
| 2 | 456 | 0.081718 | 0.160901 |
| 3 | 684 | 0.034330 | 0.177754 |
| 4 | 912 | 0.017036 | 0.191999868 |
| 5 | 1,140 | 0.008565 | 0.205720 |
| 6 | 1,368 | 0.004205 | 0.221113 |
| 7 | 1,596 | 0.001966 | 0.232121 |
| 8 | 1,824 | 0.000891 | 0.248418 |
| 9 | 2,052 | 0.000334 | 0.268335 |
| 10 | 2,280 | 0.000092 | 0.277174 |

训练 loss 随轮数持续下降，验证 loss 从第 1 轮的 0.159281 上升到第 10 轮的 0.277174，增加约 73.97%；第 1 轮是记录中的最低验证 loss。启动配置未设 load_best_model_at_end，不能把早期最低 loss 自动解释为最终输出 checkpoint。逐轮指标见 training_metrics.csv，逐步指标见 training_step_metrics.csv，完整标量数据保留在 ../materials/training/events.out.tfevents.1788622172.n30010.310.0。

脚本和日志能证明单机配置为 8 个 GPU 设备编号（0–7），但日志未记录 GPU 型号；无法根据现有历史证据填写具体 GPU 类型。
