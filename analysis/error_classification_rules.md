# 逐样本失败类型：分类规则与抽样核验

## 统计单位与判定顺序

每个 run 的统计单位是一个 `sample.id`，不是测试命令、构建日志、JUnit 方法或日志中的诊断行。分类器只读取报告中非零退出码或超时的命令记录；同一样本的重复 Maven/Gradle reactor 输出不会重复累加。基础组 178 个 `failed` 和离线组 50 个 `failed`、1 个 `error` 均各有且仅有一条标签。

分类器用以下证据优先级确定互斥的首要失败类型：

1. run 记录为 `error` 且错误明确指出不完整 Java 源文件时，记为 `output_extraction_error`。这发生在文件写入和测试命令前。
2. 若任一失败命令明确到达测试阶段且记录断言/预期结果不符，则记为 `assertion_or_expected_result`。实际运行时异常或测试错误另记 `runtime_exception`；命令超时记 `runtime_timeout`。这类具体测试结果优先于泛化的 Maven `BUILD FAILURE` 或其他重复文本，避免仅因日志包含 `Compilation failed` 就盖掉断言。
3. 若没有更具体的测试期故障，则优先依据与样本生成目标文件路径相符的编译诊断，分为 `compile_syntax_or_structure`、`compile_missing_symbol`、`compile_type_or_api`；其余明确的编译/静态检查失败归为 `compile_other`。只有缺少目标文件直接诊断时才使用项目构建诊断，并将置信度下调。
4. 明确的依赖解析、下载、守护进程或文件系统失败归入 `build_or_test_infrastructure`。证据无法定位时保留 `other_unclear`，不猜测。

分类标签含义：

| 标签 | 典型日志依据 |
|---|---|
| `compile_syntax_or_structure` | EOF、注释/字符串未闭合、非法语法、缺失方法体、类与文件结构不符、重复定义 |
| `compile_missing_symbol` | `cannot find symbol`、缺失类/方法/变量/包或目标声明不可见 |
| `compile_type_or_api` | 类型不兼容、参数签名/构造器不匹配、静态上下文错误、接口/API 调用不匹配 |
| `compile_other` | 明确编译期失败但不宜进一步归到以上子类，如 `-Werror` 将源码警告升级为失败、构造器字段未初始化 |
| `assertion_or_expected_result` | JUnit/Gradle/Maven 测试实际运行，断言或期望值不符 |
| `runtime_exception` | 测试执行产生未捕获异常或测试错误数大于零 |
| `runtime_timeout` | 测试命令超时 |
| `build_or_test_infrastructure` | 依赖下载、仓库解析、权限、磁盘或构建守护进程等环境故障 |
| `output_extraction_error` | 生成输出存在但未被抽取为完整可写入的 Java 源文件 |
| `other_unclear` | 现有日志不足以确认以上任一原因 |

## 抽样核验

对每组各失败类型做了分层日志抽查，并核对错误是否与唯一目标文件、命令结果相符：

- 基础组：类型/API `difficulty3_01e1f67fba763b1c` 报静态上下文调用错误；缺符号 `difficulty3_007a1c881368f62d` 报 `cannot find symbol`；语法/结构 `difficulty3_00857ff9602b2603` 报缺失方法体；断言 `difficulty3_94accda3b6823d56` 报实际为 `NullPointerException`、期望 `IllegalArgumentException`；运行异常 `difficulty3_6ce409f6ba9d5afa` 的测试日志记录 `NumberFormatException`。编译其他类抽查 `difficulty3_fd0990d13174bc4d`：两条冗余 cast warning 被项目 `-Werror` 规则提升为编译失败，未误记为测试断言。
- 离线组：类型/API `difficulty3_2e8e1d9181eab68a` 报无匹配的 `getObject` 方法签名；缺符号 `difficulty3_1f19fd91c0f702e9` 报符号缺失；语法/结构 `difficulty3_21b1ac760b9d6f0a` 报注释未闭合及文件结尾解析失败；断言 `difficulty3_32c0a2af7ec1083b` 报 `expected true, was false`；运行异常 `difficulty3_2998cfe41c5280eb` 报测试期空指针；提取错误 `difficulty3_adf0c54aecaaa590` 在完整源文件提取阶段报错。编译其他类抽查 `difficulty3_8e2a9e607329ce95`：目标文件 raw-type warnings 在 `-Werror` 下失败。

总体 229 个非通过样本中，224 条为高置信、5 条为中置信编译诊断；没有 `other_unclear`。中置信记录仍有明确的编译失败证据，但编译器信息不足以支持更细原因，列入 `compile_other` 或由相邻符号诊断归类。详细标签、置信度、目标文件及节选证据在 `error_classification.csv`。

## 汇总解释

| 首要故障类型 | Base n=220 | SFT-offline n=220 |
|---|---:|---:|
| 编译：语法/结构 | 115 | 14 |
| 编译：缺少符号 | 40 | 8 |
| 编译：类型/API | 16 | 13 |
| 编译：其他 | 3 | 1 |
| 测试断言/预期值 | 3 | 8 |
| 测试运行异常 | 1 | 6 |
| 测试超时 | 0 | 0 |
| 构建/测试基础设施 | 0 | 0 |
| 输出提取错误 | 0 | 1 |
| 原因不清 | 0 | 0 |

修复配对中的 131 个 fail→pass 样本，基础组原始错误分布为：语法/结构 87、缺少符号 25、类型/API 14、编译其他 2、断言 3。47 个 fail→fail 的残存样本对应：语法/结构 28、缺少符号 15、类型/API 2、编译其他 1、运行异常 1。

按领域的配对状态为：企业信息化 repaired 45、residual 20、new failure 2、new error 0、stable pass 9；智慧物流分别为 60、17、1、0、24；智慧城市分别为 26、10、0、1、5。整体为 repaired 131、residual 47、new failure 3、new error 1、stable pass 38。3 个新增失败不是同一种原因：DistributedBarrier 的测试断言 1 个，openTCS `TransportOrderConverter.java` 类型/API 编译错误 1 个，HttpComponents `CacheableRequestPolicy.java` 对 `final` 局部变量重复赋值 1 个；另有 QuotedStringAttribute 输出提取错误 1 个。

“测试断言/运行异常”在 SFT 组的绝对数量更高，反映更多生成文件通过编译并进入了测试阶段，不应直接解释成同量的新增语义回退。跨条件真实回退应依据配对状态：3 个 pass→fail 和 1 个 pass→error。所有领域、整体和分类的逐项计数见 `failure_modes_by_domain.csv`；配对结果及分类交叉见 `paired_failure_outcomes.csv`、`paired_failure_mode_transitions.csv` 和 `paired_sample_failure_transitions.csv`。