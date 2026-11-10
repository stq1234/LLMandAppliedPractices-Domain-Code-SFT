# Java Sample Builder

项目根目录中的统一入口：

```bash
./build_samples.sh
./build_samples.sh --tasks 1,2
./build_samples.sh --tasks 3
```

参数可选择 `1`、`2`、`3` 或 `difficulty1`、`difficulty2`、`difficulty3` 的逗号分隔子集。未选择任务的 JSON 会原样保留。构建过程先在 staging 目录完成并校验，成功后直接替换 `samples`，不生成备份。文件锁会阻止两个构建进程同时替换样本目录。

任务定义：

- `1.json` / `difficulty1`：每个合格函数独立生成原 API 调用样本和一个行级样本（对应候选存在时）。行级挖空在函数体内随机选择连续 1～3 个完整物理行，每行去除空格、缩进、换行等空白后，可见字符数必须严格大于 3，不能跨越不合格行。保留原始缩进与换行；JavaDoc 需求、PRO/上下文、测试筛选与覆盖率标准复用原任务一逻辑。没有 API 调用的函数也可以生成行级样本。
- `2.json` / `difficulty2`：候选函数必须有 JavaDoc、存在直接测试，并在覆盖率开关开启时达到设定阈值。每个合格函数分别生成一个函数内部 AST 样本和一个完整函数体样本（含外层大括号）。内部 AST 排除完整函数体，优先非 API 节点，只有 API 候选时允许回退；完整函数体不再参与内部 AST 的随机抽取。没有可用内部 AST 候选时（例如空函数体）只生成完整函数体。
- `3.json` / `difficulty3`：全文件或跨文件生成。Java 构建器独立扫描仓库中的业务类及其相关测试，以整个类为范围计算合并行覆盖率，并选出候选；它不依赖任务一、二的样本。草稿写入目标源码信息、空需求、目标及项目业务代码完整依赖闭包的骨架和测试信息。随后在项目根目录生成 `task3-samples-draft.zip`，压缩包中只有 `<repo>/3.json`。本地脚本补充需求后逐仓库回传覆盖。

外层 `tag`、`task`、`difficulty` 编号仍为 1/2/3；子类只依赖现有标注字段区分：

| 子类 | task/tag | completion_kind | ast_node_kind |
| --- | --- | --- | --- |
| 行级挖空 | difficulty1 | lines | LINE_RANGE |
| API 调用 | difficulty1 | expression | METHOD_INVOCATION |
| 函数内部 AST | difficulty2 | expression / statement / block | 实际 AST 类型 |
| 完整函数体 | difficulty2 | function_body | FUNCTION_BODY |
| 文件级生成 | difficulty3 | files | 沿用原字段 |

旧 `block / BLOCK` 同时可能代表内部块或完整函数体，不能仅凭这两个字段可靠区分。新构建器为完整函数体写入专用标注；旧标注仍可测评，但在未重建时归入内部 AST。需要准确统计新五类时，先运行 `./build_samples.sh --tasks 1,2`，再重新生成 completion 并测评。历史 evaluation-runs 不做迁移。

任务三重新构建时，总是按当前的候选条件和生成逻辑重建全部样本字段，因此不再符合覆盖率、源码长度等条件的旧样本会被删除。默认情况下，只按稳定样本 ID 继承旧 `requirement`；显式指定 `--cover` 时不继承，所有任务三样本的 `requirement` 都重置为空字符串。

生成的 Maven 与 Maven Wrapper 测试命令及其预编译回退命令会传入 `-Dcheckstyle.skip=true` 和 `-Dformatter.skip=true`，避免样本测试被项目级格式或风格检查阻断。

三个任务都由 `JavaSampleBuilder` 从项目根目录下的 `repos/` 独立发现候选；任务三额外读取 `skeletons/<repo>/skeletons.jsonl`。服务器端不再使用单独的 Python task3 构建器。JSON 中 `repo` 是仓库文件夹名，`repo_tag` 是 GitHub owner/repo，源码路径均相对各自仓库根目录且不带 `<repo>/` 前缀。每个仓库的 `metadata.json` 只保存 `task1`、`task2`、`task3`、`total` 四个计数。

覆盖率开关和阈值位于 `src/CoverageAnalyzer.java` 的 `ENABLE_LINE_COVERAGE_FILTER` 与 `MINIMUM_LINE_COVERAGE` 常量。开启时，对同一函数或类关联的全部成功测试逐行去重合并，再用合并后的覆盖率判定阈值；任务一、二仍要求 JavaDoc 和直接测试，任务三要求类存在直接相关测试。成功缓存继续沿用原有逐行覆盖格式，不增加状态字段也不因读取而改写；失败缓存使用 `status\tfailed`。`RETRY_FAILED_TASKS` 默认为 `false`，命中失败缓存时直接跳过；设为 `true` 时重跑失败测试并按本次结果覆盖缓存。未命中缓存的测试正常执行并写入成功或失败结果。
