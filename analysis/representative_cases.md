# 代表性样本

## Fail → pass：long 到 int 的窄化转换

ID `difficulty3_b1a5f27af4b7f4d0`，openTCS `Couple.java`（智慧物流）。基础组 `hashCode()` 直接返回 `x ^ y`，而 `x`、`y` 是 `long`，Java 编译器报 `possible lossy conversion from long to int`，对应 Gradle 命令退出码 1。0826 标识的离线输出显式写成 `(int) (x ^ y)`，同一命令退出码 0，样本从 failed 转为 passed。这是一个具体的类型修正案例；通过仅表示该样本关联命令全部成功。

## Pass → fail：等待超时单位换算方向颠倒

ID `difficulty3_32c0a2af7ec1083b`，Apache Curator `DistributedBarrier.java`（企业信息化）。ground truth 将输入 `maxWait` 从 `unit` 转为毫秒：`TimeUnit.MILLISECONDS.convert(maxWait, unit)`。离线输出写为 `unit.convert(maxWait, TimeUnit.MILLISECONDS)`，换算方向相反，随后又把结果当毫秒等待。`TestDistributedBarrier.testBasic:175` 出现 `expected: <true> but was: <false>`；基础组通过，离线组关联命令有失败。该错误与超时值被错误缩小相符。

## Pass → error：完整文件提取失败

ID `difficulty3_adf0c54aecaaa590`，Eclipse Leshan `QuotedStringAttribute.java`（智慧城市）。预测文件里有非空 completion，但内容反复重复 Javadoc 段落并在不完整的注释中结束，缺失完整 Java 文件结构。离线提取器报 `IllegalArgumentException: Model did not return a complete Java source file`，run 的 `model_output` 为空、`tests=[]`、`test_count=0`。这不是测试通过/失败结果，记作 error，但仍进入 SFT 的 220 样本分母；base 同一 ID passed。