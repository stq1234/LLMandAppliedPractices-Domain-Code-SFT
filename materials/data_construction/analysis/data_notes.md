# 数据构造核查记录

## 范围与证据

本记录只核查 a6000 上 `/home/shaotianqi/LowCode` 的仓库与数据构造脚本、catalog、`samples`、`samples_all`、`sft/{sft,train,eval}.jsonl`。没有读取 `evaluation-runs` 或训练worker负责的 Jinan v4 训练数据，没有启动服务，也没有执行仓库构建、覆盖率或 SFT 项目脚本。计数通过内联 `python3 -B` 标准库只读解析 JSON/JSONL 得到。

远端源文件只读下载至本目录。核心文件清单及原始路径见 [`source_manifest.json`](../source_manifest.json)。构造源码副本位于 [`scripts/remote`](../scripts/remote)。本地需求填充脚本副本位于 `scripts/local_redacted/`，仅在该副本中把硬编码认证值改为环境变量读取；原文件未改动。需求 prompt 与本地脚本说明存于 `reference/`。

## 仓库候选集与星数门槛

候选目录存在三个不同计数口径：本地 `repository_catalog.csv` 有 70 行；a6000 的 `repository_catalog.json` 有 74 个仓库名，但只有 70 个唯一 GitHub URL；远端 `repository_summary.csv` 有 16 条 Java 项目概述；`repos/` 当前实际包含 10 个仓库目录。因远端 catalog 中出现同一 URL 对应多个仓库名，正文引用“原始仓库数”时应说明采用的口径；按唯一上游 URL 计为 70，当前已克隆的构造输入为 10。

两份 catalog 的 stars 快照范围为 54–39,621，低于 100 stars 的条目有 3 个；当前 10 个克隆仓库的 stars 均不少于 140。检查过的 `build_samples.sh`、`JavaSampleBuilder.java`、sampler README 与运行说明没有固定 stars 筛选常量或筛选实现。因而现有证据不能确认“至少多少 stars”的门槛，也不应以当前仓库最低 star 数倒推门槛。`JavaSampleBuilder` 实际按目录发现仓库，并要求项目根目录有 Maven 或 Gradle 构建描述。

当前 10 个输入仓库及领域为：企业信息化 `canal`、`curator`、`eventmesh`、`httpcomponents-client`、`yudao-cloud`；智慧城市 `geoapi`、`leshan`、`mapfish-print`、`tinfour`；智慧物流 `opentcs`。当前 task-3 220 条中 `canal` 为零条，其余九个仓库都有样本。

## Java 样本筛选与构造

`JavaSampleBuilder` 解析仓库的 `src/main/java` 与 `src/test/java` Java 源码，通过测试调用关系把测试映射到方法或类。任务一、二面向带 JavaDoc 的普通方法，任务一生成 API 调用表达式以及满足行规则的连续 1–3 行片段；任务二生成方法体内部 AST 片段和完整方法体。任务三单独以类为目标，不依赖任务一、二的已有样本。当前实验重点是完整文件任务，因此以下详细规则只展开任务三。

任务三先为测试直接调用到的类建立候选，再按源文件路径去重：同一路径出现多个类候选时保留覆盖率较高者，样本 ID 由仓库名与相对路径的 SHA-256 前 16 位确定。目标须位于 `src/main` 且不是 `src/test` 路径，目标源码不超过 10,000 bytes；当前每条样本正好一个目标文件。样本携带相对路径、源码 SHA-256、完整目标源码、目标 skeleton、项目内依赖骨架闭包与关联测试。

覆盖率开关当前开启，阈值为严格大于 0.50。覆盖率对象是目标方法或目标类的可执行行，不是整仓库或整个源文件；任务三按类源码范围汇总直接相关测试的 JaCoCo 行覆盖结果，对命中行和可执行行编号去重后计算比例。入选需要有关联测试、至少一个带正向覆盖的成功测试结果，且合并覆盖率严格高于 50%。这不等于要求仓库的整个测试套件全部通过。当前 `CoverageAnalyzer` 配置为只读覆盖率缓存并默认不重试失败缓存，因此代码能证明的是样本由可用覆盖率结果筛选，不能据此断言这次读取时重新执行了每个测试命令。

样本源码统计支持这一门槛：当前 220 条均有非空测试列表，合计 933 个关联测试记录；每条 1–89 个，中位数 2。记录覆盖率最小值 0.5077，中位数 0.8788，最大值 1.0；220 条的 ID、仓库相对路径及源码哈希均唯一。领域分布为企业信息化 76、智慧物流 102、智慧城市 42。按仓库计数见 [`data_statistics.csv`](data_statistics.csv) 与 [`current_task3_source_index.csv`](current_task3_source_index.csv)。

## 文件 skeleton 与需求文本

`JavaSkeletonExtractor` 只发现 `src` 内的 Java 主源码，排除测试目录及构建、生成代码目录；用 JDK AST 解析后按源文件输出 JSONL。记录含仓库、相对路径、package、声明类型、import、引用类型、源码 SHA-256 和 skeleton。Skeleton 保留类/成员结构与签名，移除字段初始化值和方法体。依赖类型从完整源码（包含方法体）中的类型引用收集，task-3 构造器据引用关系递归得到项目内依赖 skeleton 闭包。

远端 Java 构造器输出 task-3 草稿时，`requirement` 可为空并记录目标源码及 skeleton。随后本地需求填充器依据角色 prompt 组装仓库上下文、目标路径、目标 skeleton、完整依赖 skeleton 与目标文件全文，以完整源码为行为依据生成中文详细需求；已填充的需求随样本保留，SFT 构造器复制到 `detailed_requirement`。当前样本的 `requirement_model` 字段为 `gpt-5.6-sol`，本地 helper 中配置的模型为 `gpt-5.6-luna`，元数据与脚本配置不一致，因此无法把字段作为已核实的实际生成模型。

## SFT 数据关联与划分

当前 `/home/shaotianqi/LowCode/sft/sft.jsonl` 有 1,226 条，ID 集合与 `samples_all/*/3.json` 完全匹配；当前 `samples/*/3.json` 有 220 条文件级功能样本。原始 SFT 划分标签对应 984 条训练数据和 242 条共享留出评估来源。数据统计索引将 SFT 记录 ID 回连到 `samples_all` 中的 `target_files[0]`，得到仓库、领域、source_path、source_sha256 与 line_coverage；这些字段本身不在 SFT JSONL 行内。

`build_sft.py` 校验每个目标路径位于仓库内、目标文件存在且 SHA-256 与样本一致，然后按目标文件生成一条 SFT 记录。记录字段为 `id`、`prompt`、`source`、`skeleton`、`detailed_requirement` 和 `only_sft_train`；源码注释当前保留。样本可以存有整个依赖 skeleton 闭包，但 prompt 先加入目标 skeleton，再添加闭包后去重；`build_cross_file_context` 只渲染去重列表第一项，因此实际训练 prompt 只包含目标 skeleton，没有使用余下依赖 skeleton。prompt 的文件起始 marker 是 `The complete Java source begins below:`。

SFT 数据以原始样本为单位按仓库切分：每仓库从非 `only_sft_train` 样本中用固定 seed 和仓库名初始化随机数生成器，抽取不超过 `floor(样本数 / 5)` 条进入脚本命名为 `eval` 的留出文件；同一原始样本的多个目标文件会一起分组。当前所有样本恰好一目标文件。此原始标签 `eval` 在本实验中表示训练期间评估与功能测试共享的留出来源。1,226 条 SFT 源记录的领域分布为：企业信息化 416（训练336、共享留出80），智慧物流510（408、102），智慧城市300（240、60）。其中 `only_sft_train=true` 共703条，全部留在训练数据；其余523条分为训练281条与共享留出242条。

重要关联：当前 `samples` 目录中的 220 个 task-3 ID 全部命中共享留出评估来源，且没有命中训练数据。文件级功能评测统计按这 220 条最终保留记录计算；训练期间评估与功能评测复用同一留出来源。原始共享留出来源比最终功能评测子集多 22 条，差额按领域为企业信息化4、智慧城市18、智慧物流0。SFT 全量 ID 和当前220条路径/源码哈希分别由 [`sft_source_index.csv`](sft_source_index.csv) 与 [`current_task3_source_index.csv`](current_task3_source_index.csv) 提供，便于与另一训练数据集交叉核对。

## 证据边界

- stars 的固定门槛与候选 catalog 的筛选时间点未在可见文件中记录；不从现存星数或当前十个 clone 推断门槛。
- 覆盖率通过可用缓存支撑候选筛选，但当前配置会跳过缓存 miss；不能仅凭脚本常量声称当时新鲜执行了全部原始测试。
- `requirement_model` 元数据与本地生成脚本配置冲突，模型版本留空不写入结论。
- 当前 `samples` 与 `sft` 输出来自不同观测根目录（220 与 1,226 条）；按记录 ID 可确认包含关系，但不能仅凭现状确定历史执行命令或两个目录的生成时间顺序。
