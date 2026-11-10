# 面向领域的 Java 文件级代码合成：数据与代码材料

本目录保存 Java 仓库筛选、AST 骨架提取、文件候选构造、需求生成、SFT 转换和统计所需的代码副本与说明。下表按模块职责和数据接口组织，描述的是组件之间的数据依赖关系，不是实验执行时间线，也不表示本目录提供一键运行的完整环境。

本次整理没有运行仓库构造、需求生成、训练或评测脚本，也没有发起模型调用。归档的文件用于说明源码、配置和数据结构；不能据此声称这些入口已在当前工作区端到端复现。

## 模块与输入/输出接口

| 模块 | 归档入口与依赖 | 主要输入 | 主要输出及数据契约 |
|---|---|---|---|
| 领域仓库目录 | [`scripts/remote/repository_catalog.json`](scripts/remote/repository_catalog.json)、[`repository_summary.csv`](scripts/remote/repository_summary.csv) | 仓库身份、领域标注及仓库源码目录 `repos/<repo>/` | 仓库名、GitHub 标识、领域标签和星标元数据；stars 是目录属性，构造器未据此设置固定下限。 |
| Java 骨架提取 | [`build_skeletons.sh`](scripts/remote/build_skeletons.sh)、[`JavaSkeletonExtractor.java`](scripts/remote/JavaSkeletonExtractor.java) | `repos/<repo>/` 中的主源码 Java 文件 | `skeletons/<repo>/skeletons.jsonl`；逐文件记录仓库路径、包名、导入、声明类型、源码引用、源码哈希及不含字段初值和方法体的骨架。测试、构建产物和生成代码不作为骨架目标。 |
| 文件候选与测试覆盖 | [`build_samples.sh`](scripts/remote/build_samples.sh)、[`JavaSampleBuilder.java`](scripts/remote/JavaSampleBuilder.java)、[`CoverageAnalyzer.java`](scripts/remote/CoverageAnalyzer.java)、[`JavaBuildTool.java`](scripts/remote/JavaBuildTool.java) | 仓库源码与测试、`skeletons/<repo>/skeletons.jsonl`、仓库领域目录 | `samples/<repo>/1.json`、`2.json`、`3.json`、`metadata.json` 及任务三草稿归档。任务三以完整源文件为目标，覆盖率按候选类计算；相关成功测试的可执行行合并后须严格超过 0.50。可选的训练扩充记录以 `only_sft_train` 标记且没有评测测试。 |
| 文件行为需求 | [`scripts/local_redacted/generate_task3_requirements.py`](scripts/local_redacted/generate_task3_requirements.py)、[`generate_task3_requirements.README.md`](reference/generate_task3_requirements.README.md)、[`task3_requirement_prompt.txt`](reference/task3_requirement_prompt.txt) | 任务三样本、目标文件完整参考源码、目标骨架和项目依赖骨架 | 在 `requirement` 字段中写入中文文件级行为说明。提示词以源码作为行为依据，并使用目标/依赖骨架描述结构与边界；本实验需求模型名称为 GPT 6 Luna。归档的 helper 副本已将明文凭据替换为环境变量读取；原脚本未被修改。 |
| SFT 样本转换 | [`build_sft_samples.sh`](scripts/remote/build_sft_samples.sh)、[`build_sft.py`](scripts/remote/build_sft.py) | 每仓库 `3.json` 与 `repos/<repo>/<target_path>` 完整源码 | UTF-8 JSONL，一条目标文件对应一条记录；字段包括 `id`、`prompt`、`source`、`skeleton`、`detailed_requirement`、`only_sft_train`。生成前验证目标源码 SHA-256。SFT 目标 `source` 是完整源码；当前提示只渲染去重骨架中的首项，目标骨架位于首位。 |
| Java 样本来源对齐 | [`extract_sft_java_samples.py`](scripts/remote/extract_sft_java_samples.py) | split JSONL 中的 Java 样本 ID 与每仓库 `3.json` | 将选定 ID 映射回 Java 样本文件，供来源核对及 Java 子集分析。已有的 [`sft_source_index.csv`](analysis/sft_source_index.csv) 和 [`current_task3_source_index.csv`](analysis/current_task3_source_index.csv) 记录 ID、领域、划分、目标路径及源码哈希。 |
| 监督微调 | [`materials/training/`](../training/) 中的 [`commands_sft_aixcoder.sh`](../training/commands_sft_aixcoder.sh)、[`deepspeed_zero3.yaml`](../training/deepspeed_zero3.yaml)、训练入口和定制 TRL 代码 | 训练材料中的 V4 训练数据与共享留出评估来源，基座为 AiXcoder 7B V2 | 训练检查点、trainer state、日志与 TensorBoard 记录保存在训练材料中。与本研究相关的 Java 数据包含 984 条训练样本和 220 条按功能实验最终保留的评估样本；训练期间评估与文件级功能评测共享同一留出来源。原始 JSONL 未纳入本材料目录；[`data/v4_sample_index.csv`](../training/data/v4_sample_index.csv) 是由原始记录整理的索引，不是训练数据本身。 |
| Java 文件功能评测 | [`materials/evaluation/sources/`](../evaluation/sources/) 中的评测入口和 Java evaluator | Java 任务三样本、仓库源码、待测预测 JSONL 与关联测试定义 | 按文件记录源码提取、构建和测试结果，并汇总通过率及错误类别；相应的评测源码、预测和已归档结果见 [`materials/evaluation/`](../evaluation/)。功能评测统计按最终保留的 220 个 Java 文件计算。 |

## 样本与划分说明

任务三 JSON 样本将文件级监督目标、行为需求、目标骨架、项目依赖骨架闭包、目标源码哈希、覆盖率、关联测试及训练用途标记关联在同一记录中。需求生成使用目标源码和依赖骨架；SFT 转换验证目标源码哈希，并把完整 Java 文件作为监督目标。样本存储的完整依赖闭包不等于 SFT 提示实际使用的上下文，具体提示字段以 `build_sft.py` 为准。

Java source set 索引保存 1,226 条记录，原始划分标签 `train` 与 `eval` 分别对应训练数据与共享留出评估来源。构造器按仓库确定性抽取留出候选，单仓目标数量不超过 `floor(样本数 / 5)`，`only_sft_train` 样本不进入该留出来源。训练与功能评测复用同一留出来源；文件级功能评测统计按最终保留的 220 条 Java 样本计算。原始索引及筛选关系见 [`analysis/data_revision_notes.md`](analysis/data_revision_notes.md)、[`analysis/data_statistics.json`](analysis/data_statistics.json) 与 [`analysis/current_task3_source_index.csv`](analysis/current_task3_source_index.csv)。

## 归档索引

- [`scripts/remote/`](scripts/remote/)：从 `a6000:/home/shaotianqi/LowCode` 只读归档的构造器、依赖源码、配置和说明。
- [`scripts/local_redacted/`](scripts/local_redacted/)：本地需求生成器的凭据脱敏副本。
- [`reference/`](reference/)：构造说明、目录快照、完整需求 prompt 及其展示版。
- [`analysis/`](analysis/)：统计、来源索引、流程说明、附录案例和绘图代码。
- [`figures/`](figures/)：流程图与三领域监督数据分布图，均有 PNG 和 SVG 格式。
- [`source_manifest.json`](source_manifest.json)：数据构造代码的原始路径和归档方式。
- [`materials/training/source_manifest.csv`](../training/source_manifest.csv)、[`materials/evaluation/source_manifest.csv`](../evaluation/source_manifest.csv)：训练及评测材料的来源记录。
