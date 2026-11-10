# 数据复杂度扩充：工程说明

## 统计来源与范围

- `../training/data/v4_sample_index.csv`：原始 V4 训练文件与共享留出评估文件的归档索引，含 `source_chars`、`source_lines`、`prompt_chars`、`skeleton_chars` 字段；本次仅选 `language=java`。V4 `train` 对应训练记录，`test` 对应共享留出评估来源；功能评测样本从该评估来源中选取。
- `sft_source_index.csv`：1,226 条 Java 记录的样本 ID、领域、仓库、路径、拆分和 `only_sft_train` 标签。与 V4 Java 子集的 ID 完全一致，目标源码精确 SHA-256 也逐条一致。
- `data_statistics.json`：已有 Java 三领域和 train/eval 基数，用作交叉校验。分析只读已归档索引，没有重新扫描远程仓库、运行训练或模型。

原始 Java 来源索引和 V4 Java 索引各有 1,226 条，原始 source set 为训练 984 条、共享留出评估 242 条。当前功能评测保留的 220 条记录按 ID 和精确源码 SHA-256 均匹配共享留出来源；未保留记录为 22 条。原始来源分布为企业信息化训练336/留出80、智慧物流408/102、智慧城市240/60；最终功能评测样本为76/102/42。图和长度统计只绘制984条训练与220条最终功能评测记录，原始留出候选及筛除记录仅作为工程来源信息保留。`sft_source_index.csv` 的原始 `train`/`eval` 标签在这里分别解释为训练数据/共享留出评估来源。训练期间评估与功能测试复用这一留出来源。

V4 Java ID 与来源索引逐条匹配，`source_sha256_exact` 与来源索引 `source_sha256` 1,226 条均一致。复杂度分析对训练记录使用训练 ID 集，对共享功能评估统计则使用 `current_task3_source_index.csv` 中最终保留的 220 个 ID 过滤后再计算；脚本验证 ID、精确哈希、领域计数与 `only_sft_train` 标记。原始 `train`/`test` 的 984/242 拆分与 `eval` 文件名均保留在来源索引，不作为图中的分析组别。

`source_lines` 按 LF 分段数解释，等于源码字符串中的 LF 数加一；空行、注释和末尾 LF 对应的空段均会计入。它不是非空代码行数。`source_chars`、`prompt_chars`、`skeleton_chars` 直接使用归档索引中的字段，不重新解析训练 JSONL，也不折算为模型词元；其中 `prompt_chars` 对应 V4 源记录的 `prompt` 字符串长度，`skeleton_chars` 对应该记录单数 `skeleton` 字段的目标骨架字符串长度，不是依赖闭包中全部骨架长度之和。图表每个箱体覆盖 P25–P75，中位数为横线，须线定义为 P5–P95，纵轴使用对数刻度；须线外的点不绘制。表格及 JSON 中分位数采用在排序值 `(n-1)q` 位置上线性插值的定义。

## 数据采样角度的比较与采用

对照阅读了：

- `作业2.1_Gensim_FastText编程实验/实验报告.md`：明确文本单元、类别弱标注、词数过滤、按类别定额取样与词汇重叠。
- `作业3.1_BERT标点恢复/report.md`：以字符位置为单位统计数据拆分与类别支持量，并讨论序列标签不平衡。
- `作业4.2_唐诗宋词英译/实验报告.md`：以少量目的性样本为单位，根据语义现象设计样本和测试集合。

本次扩充只采用适合 Java 文件合成的报告习惯：明确定义一个样本对应的目标单元和每种长度单位，按领域与训练/共享评估两种分析组同时展示统计量，并解释最终功能实验保留样本的适用范围。未沿用其他任务的定额类别抽样、字符位置样本单位或诗歌语义样本设计。

## 依赖骨架闭包的源码核对

归档 `JavaSampleBuilder.java` 的 `dependencyClosure()` 采用队列遍历：以目标骨架为根，对每个已选文件调用 `skeletonDependencies()`；只把能够在该仓库骨架记录中解析出的文件加入队列，按 `sourcePath` 去重，遍历至无新项目内依赖时停止。解析依据包括完整源码提取出的引用类型、显式 import、同包声明和通配包。`JavaSkeletonExtractor.java` 在完整源码中扫描引用类型，所以方法体中的项目类型引用也可成为遍历边。闭包不跨仓库，不包括只存在于依赖库的类型。源码没有显式最大深度或最大文件数参数；上界由当前仓库 Java 骨架记录和可解析引用决定。闭包结果先放目标文件、其余记录按路径排序。

需求生成阶段输入目标完整源码以及目标/依赖骨架信息。当前 `build_sft.py` 与 V4 的实际提示构造不能混同；本统计只针对 V4 索引记录中的 `prompt_chars` 与 `skeleton_chars`，没有用当前 renderer 重建 V4 prompt，也没有把存储闭包大小当作 V4 prompt 长度。图中的评估长度分布仅基于最终功能实验保留的 220 个 ID。

## 本地仓库快照差异

已有只读核对发现，本机当前克隆的 Java 文件都能按仓库相对路径找到，但其中 144/1,226 条与 V4 索引的源码哈希和字符长度不一致。为保持数据口径与训练版本一致，本统计只使用 V4 索引中归档的字段；不会从当前仓库快照重新计算并覆盖这些值。该本地快照差异不作为训练源代码内容发生变化的推断依据。

## 复用与产物

执行：`python -B analysis/analyze_data_complexity.py`（从 `materials/data_construction` 或在脚本可访问的 Python 环境中运行）。脚本依赖 Python 标准库与 Matplotlib，只读取上述两个 CSV 和 JSON，生成：

- `analysis/data_complexity_records.csv`：训练984条与共享功能评估220条逐样本记录及领域、长度字段。
- `analysis/data_complexity_summary.csv`：按领域、训练/共享评估两组、指标计算的 N、min、P25、中位数、P75、P90、max、mean。
- `analysis/data_complexity_statistics.json`：指标定义、两组统计及 ID/哈希检查结果。
- `figures/data_complexity_distributions.png` 与 `.svg`：同一份多面板图。

以上表格和图仅是从现有索引派生出的本地分析材料；完整 V4 JSONL 并未加入该数据构造归档。
