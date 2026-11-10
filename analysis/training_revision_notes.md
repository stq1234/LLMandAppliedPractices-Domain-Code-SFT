# SFT 训练修订记录（工程核查）

## 0826 预测模型的权重/轮次关联

本地已核验的 materials/evaluation/sources/sft/predictions.jsonl 共 220 条，model 字段全部为 aixcoder-7b-v2-sft-0826。记录还包含 sample_index、source_index、prompt_tokens、evaluation_result 等字段，但没有 checkpoint 路径、模型权重哈希、epoch/step 或 inference run ID。

在已知 LowCode 工程的现有脚本和产物中，唯一文件名带 0826 的训练日志是 logs/7b_v2_sft_0826.log。该日志显示运行到 epoch 15、step 1,500，末轮 eval_loss=0.00011338992044329643，Trainer 汇总 train_loss=0.017331987270183768，train_runtime=9,510.6301 秒。日志没有记录输出目录、权重文件或 served-model-name，因此不能据此断定 predictions 对应此运行，更不能断定使用最终 epoch 或某个早期 checkpoint。已知 models 目录当前仅有 7b_v2_sft_v2_0829、7b_v2_sft_v2_0902 和 7b_v2_sft_v4_0905，未发现 0826 命名的模型目录。

现存 server.sh 明确将基础模型 revision 94a44b5caba59512e26da3335e1b8bdd07ffc5fa 挂载为 aixcoder-7b-v2-sft；这既不是 predictions 中的 0826 别名，也不是 v4_0905 输出模型。evaluation/inference.py 要求命令行传入 model_name 和 model_path，结果写入 model_name，但当前 predictions 不含 model_path。评测侧确认 evaluate_sft.sh 是评测 wrapper，不负责生成这些 predictions。现有证据不足以把 0826 predictions 映射到具体权重路径或训练轮次；报告应保留这一归因边界。

## 历史 GPU 证据

v4 日志和 0826 日志都没有 GPU 板卡型号。0826 日志只显示 NVIDIA 容器发行版 Release 24.05；这不是物理 GPU 型号。server.sh 的 device=6 是推理服务的设备编号，也不能作为训练硬件证据。历史 GPU 类型仍无法确定。

## v4 训练曲线复核

本轮重新读取 jinan 远程 v4 TensorBoard 事件和训练日志。远程 SHA-256 与本地 source_manifest.csv 所记录副本一致：事件文件为 3109d1d36bc40080ba7b746a1372c0f3a2cd4feb35979c7be54ec3aad56e01b8，训练日志为 edb008bb9430abcc2d416cf929540cfb8613eef3c8c8d78d101fc1ebe62443e5。远程日志中的 10 个 eval_loss 与 analysis/training_metrics.csv 逐轮一致。事件解析结果为 9,179 个 TFRecord、2,280 个 train/loss 点、2,280 个 learning-rate 点和 10 个 eval/loss 点；现有逐步 CSV 有 2,280 行数据。

保留的逐 epoch 图为 figures/02_training_validation_loss.png 与 .svg，使用中文标题/轴标；新增的 figures/training_metrics.png 与 .svg 展示逐步训练 loss、50 步移动均值、每轮验证 loss 与学习率。绘图代码位于 analysis/plot_report.py 的训练图函数，事件解码和复算入口位于 analysis/parse_training_events.py。图中 step loss 与 epoch 均值仍对应事件和训练日志的原始数值口径。

## 模型结构图与写作边界

新增 figures/model_architecture.png 与 .svg，由 analysis/plot_model_architecture.py 本地绘制。结构依据归档 final_model_config.json 的 model_type=llama、vocab_size=49152、hidden_size=4096、intermediate_size=14464、num_hidden_layers=32、num_attention_heads=32、num_key_value_heads=8、max_position_embeddings=65536、hidden_act=silu、rms_norm_eps=1e-6、rope_theta=256000、tie_word_embeddings=false；训练截断长度仍为 32768。Pre-RMSNorm、RoPE、SwiGLU 与 decoder 结构参考 [LLaMA 2 论文](https://arxiv.org/abs/2307.09288)，本模型实际维度、层数、GQA 头数按归档配置绘制，图不是论文原图。图中将 causal attention mask 与 completion-only loss label mask 分开表示。

当前写作重点可聚焦 Java，但必须保留本次训练数据混合事实：train.jsonl 为 Java 984 + Python 838，test.jsonl 为 Java 242 + Python 210。原 Java SFT 子集采用 repo 内样本级随机 eval（每 repo floor(n/5)、seed 42，only_sft_train 不进入 eval），并非 repo-heldout；v4 train/eval 共用 29 个 repo 与此设计一致。正文可以压缩通用配方并把完整配置移到附录，不能称作 Java-only 训练。220 条离线 predictions 的模型身份尚未关联到 v4_0905，不得把它们写成 v4 checkpoint 的生成结果。
