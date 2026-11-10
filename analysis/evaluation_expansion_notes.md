# 评测扩展的口径与来源说明

## 输入与配对

- 基础组评测：`materials/evaluation/runs/20261109-123917-aixcoder-v2/report.json`。
- 领域微调预测组：`materials/evaluation/runs/20261109-124514-sft-offline/report.json`。
- 评测样本索引：`analysis/evaluation_sample_index.csv`。
- 领域微调样本索引：`materials/data_construction/analysis/sft_source_index.csv`。
- V4 源码长度索引：`materials/training/data/v4_sample_index.csv`。
- 样本通过状态定义核对：`materials/evaluation/sources/java-evaluator/JavaBenchmarkEvaluator.java`。

两份报告各含 220 个唯一样本 ID，ID 集合相同。通过目标文件 SHA-256 将评测样本依次关联到 `sft_source_index.csv` 与 V4 样本索引；220/220 个样本均获得唯一精确匹配，且匹配到 V4 Java `test` 划分。此映射用于取得源文件长度，不代表评测输出由某一 V4 checkpoint 生成。

## 指标定义

### 源码提取与样本通过

候选源码提取计数以 `report.json` 中非空 `model_output` 为准。基础组为 220/220；领域微调预测组为 219/220。后者唯一未提取样本在评测报告中为 `error`，不得作为失败样本或通过样本处理。

样本级“全部关联命令成功”同时要求评测状态为 `passed`、关联命令列表非空，且每条去重命令均未超时、退出码为 0。这里的命令记录数不是 JUnit 测试方法、断言或新执行次数。每个已通过样本均有测试阶段任务或正数测试摘要记录；但通过标签仍按全部关联命令的退出状态汇总。

### 测试阶段与非缓存记录

分析脚本 `analysis/analyze_extended_evaluation.py` 读取归档命令输出，并通过仅匹配水平空白的正则表达式定位 Gradle `:test` 任务行，避免 `\s` 跨行吞入任务后续输出。日志中除 `NO-SOURCE`、`SKIPPED` 外的 `:test` 任务结果计入“测试阶段结果”，包括空状态、`FAILED`、`FROM-CACHE` 和 `UP-TO-DATE`。正数 Surefire 摘要、正数 Gradle 测试摘要也作为阶段结果证据。普通项目模块的 `No tests to run` 文本不作为正数摘要；每组报告各有 24 条命令输出包含此类提示。

“至少一条非缓存任务或测试摘要”是样本级日志分类：计入没有 `FROM-CACHE` 或 `UP-TO-DATE` 标记的 Gradle `:test` 任务结果，或正数 Surefire 摘要。任务行虽为 `FAILED` 仍属于非缓存任务结果，但这不意味着任务运行到了测试方法。正数 `Core test ... summary` 与 `N tests completed` 仅用于标记阶段有结果，不单独作为非缓存任务执行证据。因此，该次级计数不代表 JUnit 方法、断言数，也不保证同一样本下所有关联命令均为非缓存运行。

解析后的 Gradle `:test` 任务记录状态为：基础组 4 条 `FROM-CACHE`、172 条空状态、27 条 `FAILED`；领域微调预测组 221 条 `FROM-CACHE`、411 条空状态、9 条 `FAILED`，没有 `UP-TO-DATE` 记录。样本级阶段结果为 46/220 与 183/220；满足非缓存任务或正数 Surefire 摘要条件的样本为 43/220 与 143/220。另有 3 个基础组样本和 40 个领域微调预测样本仅含 `FROM-CACHE` 任务结果，逐样本日志中没有正数 Surefire、正数 Core test 或 `N tests completed` 摘要。由此，这 3/40 个样本进入阶段结果分母，但不进入非缓存任务/摘要计数。

### 源码长度分组

长度来自 V4 索引字段 `source_lines`，其计算等价于 `len(source_text.split('\n'))`：计入空行与注释行，若末尾存在换行符还会计入末尾空段。它是源码文本行段数，不是有效代码行数或复杂度测量。对 220 个文件按最近秩法计算三分位数，边界为 71 和 134；分组规则为 `≤71`、`72–134`、`>134`，分组后样本量为 75、72、73。各组来源仓库和领域比例不同，结果仅用于描述性分层，不能据此估计长度的独立影响。

配对转移列以每个样本的评测状态为准：`failed→passed`、`failed→failed`、`passed→failed`、`passed→error`、`passed→passed`。三组迁移计数各自分别合计 75、72、73，且与状态总数、图表通过率分子一致。

### 文件微平均与仓库宏平均

文件微平均为所有通过文件数除以 220。仓库宏平均为 9 个仓库内部通过率的等权算术平均：

`macro = (1 / 9) × Σ(pass_count_repo / file_count_repo)`。

微平均：基础组 42/220=19.09%，领域微调预测组 169/220=76.82%，差值 +57.73 个百分点。宏平均：10.80% 和 57.90%，差值 +47.10 个百分点。仓库文件数从 1 到 102 不等，低样本量仓库的宏平均分量波动较大；仓库宏平均只作为补充视角。

## 代表案例来源

- 样本 ID：`difficulty3_9a34df9fd4ed74fa`；仓库 `opentcs`；目标类 `PeripheralAttachmentManager`。
- 需求、目标路径、骨架与方法级测试选择器来自两组报告中同一评测样本的 `sample` 对象；基础组和领域微调预测组的 GT 字段一致。
- 基础组生成文件为非空签名骨架，关联的四条 Gradle 命令均在 `compileJava` 阶段因 `cannot find symbol` 退出码 1，日志中没有 `:test` 任务结果。
- 领域微调预测组生成了方法体；四条关联命令的选择器分别是 `shouldAttachAdapterToLocation`、`shouldAutoAttachAdapterToLocation`、`shouldGetAttachmentInformation` 和 `shouldHaveInitializedPeripheralEntryPool`。四条命令退出码均为 0，输出均包含无缓存标记的 `> Task :opentcs-kernel:test` 任务行；构建摘要分别显示 4 项或 1 项任务执行，其余为 `UP-TO-DATE`。因此，正文表述为四条命令成功且有非缓存 `:test` 任务记录，不将其写成四个独立 JUnit 断言。
- 正文中的代码节选来自该样本的 `skeletons[0]` 和领域微调预测记录 `model_output`，按行为选择片段并省略未讨论内容。通过测试命令不等同于与 GT 逐字一致；文中也未作此推断。

## 生成文件

复用脚本：`analysis/analyze_extended_evaluation.py`。脚本仅读取上述本地归档数据，写出：

- `analysis/extended_evaluation_summary.json`
- `analysis/evaluation_stage_summary.csv`
- `analysis/paired_stage_transitions.csv`
- `analysis/extended_sample_metrics.csv`
- `analysis/source_length_group_summary.csv`
- `analysis/source_length_repository_sensitivity.csv`
- `analysis/repository_rates_micro_macro.csv`
- `analysis/figures/evaluation_stage_funnel.png` 与 `.svg`
- `analysis/figures/source_length_paired_rates.png` 与 `.svg`

新增分析已整合至 [实验报告.md](../实验报告.md)：阶段结果为图 9、表 8，长度分层为图 12、表 10，微/宏平均为表 7，完整业务案例为第 7.7 节。

## 解释边界

预测报告的模型字段记录历史标识 `aixcoder-7b-v2-sft-0826`；V4 索引用于源码长度匹配，V4 训练过程不能据此认定为该预测结果的具体 checkpoint 或训练轮次。两组均覆盖同一批评测 ID，并不足以确立模型 checkpoint 或推理上下文完全可比。长度与宏平均结果均为当前样本中的描述统计。

写作结构参考了课程中的 NextBERT 设计验证报告、提示词泄漏实验报告和第 6 次作业报告，具体借鉴指标口径说明、分母呈现、分层讨论和案例组织方式；本扩展中的数值、源码和结论均来自本次评测材料。

## 核验

使用 `python -B analysis/analyze_extended_evaluation.py` 对本地 JSON/CSV 执行离线解析并重建图表；确认两份报告 ID 集合一致、220 个源码长度映射唯一、分层迁移数与组样本数相符、微/宏平均与逐仓库汇总一致。已目视检查两张 PNG，计数注记和图脚说明与表格口径相符。未运行模型、项目测试、评测服务或远程写操作。
