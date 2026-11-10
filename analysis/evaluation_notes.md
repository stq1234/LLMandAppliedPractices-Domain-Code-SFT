# 评测运行核查记录

## 范围与完整性

核查对象是 a6000 上的 `/home/shaotianqi/LowCode/evaluation-runs` 两个目录：基础组 `20261109-123917-aixcoder-v2` 与离线预测组 `20261109-124514-sft-offline`。两组 `report.json` 和 `checkpoint.json` 均记录 220 条已完成结果，样本 ID 各自唯一且两组 ID 集合相同。逐样本比较显示 220/220 的样本元数据、任务、目标路径和 ground truth 一致，因此主比较可按配对样本处理。

两份报告实际只有 `difficulty3_files`（全文件生成，报告标签 synthesis）非空：220 条，三个领域分别为企业信息化 76、智慧物流 102、智慧城市 42。snippet、api、completion、generation 均为 0 个计分样本；前两项字符分母为 0、CharacterAccuracy 显示 N/A，后两项没有 pass-rate 分母。不能把这些空项写成 0% 或有效结果。两次运行都只覆盖五项任务中的 synthesis。

| 领域 | n | 基础组通过 | 基础组失败 | 基础组错误 | 离线组通过 | 离线组失败 | 离线组错误 | 通过率变化 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 企业信息化 | 76 | 11 | 65 | 0 | 54 | 22 | 0 | +56.58 个百分点 |
| 智慧物流 | 102 | 25 | 77 | 0 | 84 | 18 | 0 | +57.84 个百分点 |
| 智慧城市 | 42 | 6 | 36 | 0 | 31 | 10 | 1 | +59.52 个百分点 |
| 合计 | 220 | 42 | 178 | 0 | 169 | 50 | 1 | +57.73 个百分点 |

通过率为 `passed 样本数 / 选中样本数`。分母按两组各自的 220 条完整评测 ID 计；error 是非通过，仍计入分母。按失败、通过、错误配对，状态迁移为：失败→通过 131，失败→失败 47，通过→通过 38，通过→失败 3，通过→错误 1，其余迁移为 0。由状态总数还可得到净增通过 127 条；通过率差由 `(169-42)/220` 得出。

## 评分与过滤口径

`evaluate_sft.sh` 执行 `bash ./evaluate_samples.sh --tasks 3 --predictions sft/predictions.jsonl`。task 3 选择时排除 `only_sft_train=true` 的样本；离线模式再按预测文件中的 ID 选样。此次输入预测有 220 个唯一 ID，评测中的 220 条全部 `only_sft_train=false`，且均能配到预测记录。样本字段显示 line-coverage filter 已启用，阈值为 0.5。`evaluation_sample_index.csv` 保存了逐样本 ID、目标路径、GT 哈希及训练标志，供与训练索引交叉核验。

全文件任务对每条样本的去重测试命令按 `(test_workdir, test_command)` 执行；只有当样本没有 `tests` 时才退回到 `build_commands`。既无测试命令也无构建命令时产生错误。只有至少有一条命令结果且全部命令未超时、退出码为 0，样本才是 passed；基础设施异常记为 error，编译或测试命令非零退出记为 failed。这里的 `test_count` 是实际记录的测试/构建命令条数，不是 JUnit 测试方法数或断言数。没有独立的“skipped”样本状态；命令日志中即使包含 Maven 子模块 `No tests to run`，也不能据此声称每条命令都执行了 JUnit 用例。

基础组有 933 条命令结果：179 条退出码 0、754 条退出码 1。离线组有 932 条：754 条退出码 0、178 条退出码 1；余下 1 条样本在文件输出提取阶段报错，没有执行命令。命令条数是执行记录统计，不应作为 933 个/932 个测试用例计算通过率。

CharacterAccuracy 定义为字符位置准确率：逐 Unicode code point 比较预测与参考序列相同位置的字符，匹配数除以参考字符数；snippet 子任务按各个目标行分别比较并累加字符计数，api 子任务比较其生成 span。汇总应先加总 matched/total，再相除，不应平均各样本百分比。本次两项字符任务无样本，因此没有可报告的准确率。

## 生成输入、上下文与模型身份

基础报告的 `model` 与 `base_url` 字段是 `aixcoder-7b-v2` 和 `http://127.0.0.1:8000`。评测源代码默认通过该地址的 `/v1/completions` 在线请求；task 3 的源代码请求参数为 `max_tokens=8192`、`temperature=0`、`top_p=0.95`、`n=1`，stop 序列为 `</s>`、`<|endoftext|>`、`▁<AIX-SPAN-POST>`，读取超时 600 秒。源代码构造的全文件提示包含目标路径、Java 标记、完整源文件生成说明、按行注释的自然语言需求以及 FIM 标记；全局声明上下文为空，similar-code 上下文最多注入首个注释化骨架。报告未保存本次逐条 prompt 或请求/响应日志，因此这些是随附评测代码中的参数与提示模板，不能验证实际在线权重，也无法证明离线预测与基础组提示完全相同。基础运行目录没有保留原始 shell 命令记录。

离线组由 `--predictions` 读取已存在的 `sft/predictions.jsonl`。该文件 220 条的 `model` 字段均为 `aixcoder-7b-v2-sft-0826`，并非报告静态字段中的 `aixcoder-7b-v2`。Java evaluator 的报告 `MODEL`/`BASE_URL` 字段来自静态默认值；离线分支读取 `completionFor`，不调用 `callModel`。因此 SFT 报告里的 `model=aixcoder-7b-v2` 与 `base_url=http://127.0.0.1:8000` 只是评测器元数据，不能代表 SFT 权重或实际网络请求。离线组 `completions.jsonl` 的 219 条有效记录，其 `files[0].raw_output` 均与对应预测 `completion` 完全一致；剩余一条预测虽然存在，但格式提取器没有产出可执行源文件，故该条未出现在成功 completion 记录中。该评测路径读取预测 completion 后替换样本目标文件并运行命令，不会用 ground truth 回填缺失输出。

预测 JSONL 记录 `requested_max_tokens` 与 `structure_token_budget` 的最小值、 中位数、最大值均为 1024、2076、11286；`prompt_tokens` 的对应统计为 2583、5914.5、10890。该文件没有逐样本 prompt 文本、温度、top-p、stop 或可识别 checkpoint 路径。脚本 `/home/shaotianqi/LowCode/sft` 下没有恢复出此次推理专用脚本；因此离线组推理提示和完整采样参数无法与基础组核对。

训练侧索引核验显示 220 条评测样本属于 v4 的 Java test 子集，但生成记录标识为 `aixcoder-7b-v2-sft-0826`，而 v4 训练 checkpoint 为 0905。现有元数据没有把 0826 预测关联到 0905 权重的证据。故这组结果可称“0826 标识的 SFT 离线预测评测”，不能归因于 v4 checkpoint，也不构成 v4 训练与功能评测闭环的因果比较。

对预测输出与 ground truth 做 CRLF→LF 规范化及首尾空白裁剪后，有 8/220 条预测与完整目标文件相同；基础输出中为 0 条。8 条 ID 见 `evaluation_summary.json`。这是输出一致性观察，不足以单独证明 GT 重放、训练污染或数据泄漏；上游推理来源和上下文未被记录，故不能排除或确认这些可能性。

## 代表案例

三条案例的完整 ground truth、两组输出、错误字段和相关测试命令节选见 `materials/evaluation/examples/failed/`，解释见 [`representative_cases.md`](representative_cases.md)。其中 fail→pass 的案例对应基础组编译类型错误被 SFT 修正；pass→fail 对应时间单位换算方向错误；error 对应 SFT 输出不完整而非测试失败。

## 工件

`materials/evaluation/source_manifest.csv` 列出本次下载的 17 个远程结果/代码/预测源文件，并记录远程绝对路径、本地字节数、SHA-256 和远程 SHA-256 比对结果。逐样本配对结果、领域统计、迁移统计、任务空值矩阵和可重跑解析器见 `analysis/paired_results.csv`、`analysis/evaluation_summary.csv`、`analysis/paired_transition_summary.csv`、`analysis/task_metrics.csv`、`analysis/build_evaluation_analysis.py`。远端只执行了文件读取、路径查找与 SHA-256 计算；没有在服务器创建/修改文件，也没有运行项目脚本、模型、评测器或服务。