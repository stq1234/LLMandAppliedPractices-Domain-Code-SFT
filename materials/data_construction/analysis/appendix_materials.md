# Java 文件生成数据附录材料

## 图表

可复用绘图代码见 [`plot_data_pipeline.py`](plot_data_pipeline.py)。图表同时保存为 PNG 和可编辑 SVG：

- [`data_construction_flow.png`](../figures/data_construction_flow.png)：从候选仓库、AST/骨架与覆盖率筛选，到需求回填、SFT 构造和划分的流程。
- [`java_domain_split.png`](../figures/java_domain_split.png)：Java 三领域训练数据与功能实验最终保留的评估样本分布；柱上计数为984条训练和220条评估。

## 提示词归档

当前需求生成 helper 读取本地 `didaima/prompt.txt`。全文归档于 [`../reference/task3_requirement_prompt.txt`](../reference/task3_requirement_prompt.txt)，并已与读取的输入逐字节核对；保留核心约束与变量占位符的展示版见 [`../reference/task3_requirement_prompt_display.md`](../reference/task3_requirement_prompt_display.md)。`prompt1.txt` 不是当前 helper 引用的模板，故不作为当前提示词。提示词文件不包含 API key。本实验需求生成模型为 **gpt-6-luna**，由用户确认为准；模型名不在提示词正文中。

## 案例 A：简单工具文件

**以下均为阅读节选，不是完整需求、骨架或源码。** 样本为 `mapfish-print_difficulty3_0ddb4e4583888c99`（仓库 `3.json` 中的 ID 为 `difficulty3_0ddb4e4583888c99`），领域为智慧城市，目标路径为 `core/src/main/java/org/mapfish/print/StatsUtils.java`。目标类的行覆盖率为 1.0，关联 1 个测试。

**真实需求节选：**“该类将一个名称转换为可作为指标名称点号分段使用的字符串：字母、数字和下划线保持不变，其他字符统一转换为下划线；空引用则转换为固定文本 `"NULL"`。”

**目标骨架节选：**

```java
public final class StatsUtils {
  private StatsUtils();
  public static String quotePart(final String name);
}
```

**已核对 SHA-256 的目标源码节选：**

```java
if (name == null) {
  return "NULL";
}
return name.replaceAll("[^A-Za-z0-9_]", "_");
```

需求准确概括了源码可见的行为：`null` 映射为字面量哨兵值，其余不属于 ASCII 字母、数字或下划线的字符被替换。该案例来自当前任务三样本，不是合成示例。

## 案例 B：物料搬运中的路线分配

**以下均为阅读节选，不是完整需求、骨架或源码。** 样本为 `opentcs_difficulty3_2176b7c6c15216e3`（仓库 `3.json` 中的 ID 为 `difficulty3_2176b7c6c15216e3`），领域为智慧物流，目标路径为 `opentcs-strategies-default/src/main/java/org/opentcs/strategies/basic/dispatching/DriveOrderRouteAssigner.java`。目标类的行覆盖率为 1.0，关联 2 个测试。

**真实需求节选：**“职责是为运输订单中的未来驾驶订单计算并附加路线。”生成的需求还说明了组件边界：`Router` 提供候选路线，`RouteSelector` 选择路线序列，目标类再将选中的路线与运输订单的未来驾驶订单对应起来。

**目标骨架节选：**

```java
public class DriveOrderRouteAssigner {
  private final Router router;
  private final DefaultDispatcherConfiguration configuration;
  private final RouteSelector routeSelector;
  public Optional<List<DriveOrder>> tryAssignRoutes(
      TransportOrder order, Vehicle vehicle, Point startPosition);
}
```

**已核对 SHA-256 的目标源码节选：**

```java
return routeSelector.selectSequence(
    router.getRoutes(vehicle, startPosition, order, configuration.maxRoutesToConsider())
)
    .map(routes -> {
      List<DriveOrder> driveOrderList = new ArrayList<>();
      for (int i = 0; i < routes.size(); i++) {
        driveOrderList.add(order.getFutureDriveOrders().get(i).withRoute(routes.get(i)));
      }
      return driveOrderList;
    });
```

该案例体现了明确的业务边界：路线计算与选择委托给依赖组件；选中路线序列后，按索引将路线应用到新的驾驶订单值对象；未选出路线时沿用空 `Optional`。这段代码不表示目标类会持久化运输订单或改变车辆状态。

## 案例阅读说明

两个案例均在当前 220 条单文件任务三样本中。每条记录将中文需求与一个完整 Java 目标文件及目标骨架配对；需求生成时还输入了项目依赖骨架，但本节只展示目标文件骨架。SFT 的监督目标是完整源码，而非这里展示的短节选。所选 ID 避开了评测案例分析 worker 正在使用的三条记录。
