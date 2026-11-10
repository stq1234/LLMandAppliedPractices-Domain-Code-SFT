# 全文件生成评测案例修订

本节从同一组 220 个配对样本中选取三个正文短对照和四个附录案例。代码块均为原始输入骨架、参考实现或模型输出的节选，行号省略；未以参考代码补全错误输出。状态来自样本级报告，命令记录数不是 JUnit 方法数。

## 正文短对照

### Couple：哈希函数的 long/int 类型转换

**仓库：** openTCS/opentcs；**文件：** `Couple.java`。

**需求摘录：**

> 这里的异或运算在 `long` 值上执行，随后通过显式转换保留低 32 位并按 Java `int` 规则解释。

**输入骨架摘录：**

```java
package org.opentcs.data.model;

import java.io.Serializable;

public class Couple implements Serializable {
  private final long x;
  private final long y;
  public Couple(long x, long y);
  public long getX();
  public long getY();
  @Override public boolean equals(Object obj);
  @Override public int hashCode();
  @Override public String toString();
}
```

**参考语义：** `x` 与 `y` 是 long；异或仍是 long，返回 int 前必须按参考实现显式窄化转换。其他对象契约保持不变。

**参考实现：**

```java
public int hashCode() {
    return (int) (x ^ y);
  }
```

**基础组输出：**

```java
@Override public int hashCode() {
    return x ^ y;
  }
```

**SFT 离线预测输出：**

```java
public int hashCode() {
    return (int) (x ^ y);
  }
```

**日志含义：**

基础组的直接编译诊断是 long 到 int 可能发生有损转换；SFT 输出增加显式 int 转换，同一关联命令成功。

基础组状态 `failed`，关联命令记录 1 条，其中非零/超时 1 条。代表性命令 `org.opentcs.data.model.ModelSerializationTest.shouldSerializeAndDeserializeEnvironmentalEntity` 的日志节选：error: incompatible types: possible lossy conversion from long to int。
SFT 离线预测状态 `passed`，关联命令记录 1 条，失败/超时记录 0 条。

### DistributedBarrier：有限等待的时间单位

**仓库：** apache/curator；**文件：** `DistributedBarrier.java`。

**需求摘录：**

> 若 `unit` 非空，则通过 `TimeUnit.MILLISECONDS.convert(maxWait, unit)` 将 `maxWait` 转换为毫秒；若 `unit` 为空，则把最大等待毫秒数视为 `Long.MAX_VALUE`，但同时通过独立的 `hasMaxWait` 标志选择无限期的 `wait()` 分支，因此不会把空单位当作一个普通的有限超时单位。

**输入骨架摘录：**

```java
package org.apache.curator.framework.recipes.barriers;

import java.util.concurrent.TimeUnit;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.PathUtils;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

public class DistributedBarrier {
  private final CuratorFramework client;
  private final String barrierPath;
  private final Watcher watcher;
  public DistributedBarrier(CuratorFramework client, String barrierPath);
  public synchronized void setBarrier() throws Exception;
  public synchronized void removeBarrier() throws Exception;
  public synchronized void waitOnBarrier() throws Exception;
  public synchronized boolean waitOnBarrier(long maxWait, TimeUnit unit) throws Exception;
}
```

**参考语义：** `maxWait` 的值以 `unit` 为单位；有限等待需把其转换为毫秒，超时后返回 false，节点消失则返回 true。

**参考实现：**

```java
public synchronized boolean waitOnBarrier(long maxWait, TimeUnit unit) throws Exception {
        long startMs = System.currentTimeMillis();
        boolean hasMaxWait = (unit != null);
        long maxWaitMs = hasMaxWait ? TimeUnit.MILLISECONDS.convert(maxWait, unit) : Long.MAX_VALUE;

        boolean result;
        for (; ; ) {
            result = (client.checkExists().usingWatcher(watcher).forPath(barrierPath) == null);
            if (result) {
                break;
            }

            if (hasMaxWait) {
                long elapsed = System.currentTimeMillis() - startMs;
                long thisWaitMs = maxWaitMs - elapsed;
                if (thisWaitMs <= 0) {
                    break;
                }
                wait(thisWaitMs);
            } else {
                wait();
            }
        }
        return result;
    }
```

**基础组输出：**

```java
public synchronized boolean waitOnBarrier(long maxWait, TimeUnit unit) throws Exception {
    long startMs = System.currentTimeMillis();
    while (client.checkExists().usingWatcher(watcher).forPath(barrierPath) != null) {
      if ((unit != null) && (System.currentTimeMillis() - startMs >= TimeUnit.MILLISECONDS.convert(maxWait, unit))) {
        return false;
      }
      wait(unit != null ? Math.min(TimeUnit.MILLISECONDS.convert(maxWait, unit) - (System.currentTimeMillis() - startMs), TimeUnit.MILLISECONDS.convert(1, TimeUnit.MILLISECONDS)) : 0);
    }
    return true;
  }
```

**SFT 离线预测输出：**

```java
public synchronized boolean waitOnBarrier(long maxWait, TimeUnit unit) throws Exception {
        long start = System.currentTimeMillis();
        boolean hasMaxWait = unit != null;
        while (client.checkExists().usingWatcher(watcher).forPath(barrierPath) != null) {
            if (hasMaxWait) {
                long thisWaitMs = unit.convert(maxWait, TimeUnit.MILLISECONDS) - (System.currentTimeMillis() - start);
                if (thisWaitMs <= 0) {
                    return false;
                }
                wait(thisWaitMs);
            } else {
                wait();
            }
        }
        return true;
    }
```

**日志含义：**

基础组四条关联测试/构建命令均成功。SFT 输出将换算方向颠倒；四条命令中两条因屏障等待测试断言失败，样本由 passed 转为 failed。

基础组状态 `passed`，关联命令记录 4 条，失败/超时记录 0 条。
SFT 离线预测状态 `failed`，关联命令记录 4 条，其中非零/超时 2 条。代表性命令 `org.apache.curator.framework.recipes.barriers.TestDistributedBarrier#testBasic, org.apache.curator.framework.recipes.barriers.TestDistributedBarrier#testServerCrash` 的日志节选：org.opentest4j.AssertionFailedError: expected: <true> but was: <false>；org.opentest4j.AssertionFailedError:。

### BoundingBoxProtrusionCheck：四向偏移与顶部高度差

**仓库：** openTCS/opentcs；**文件：** `BoundingBoxProtrusionCheck.java`。

**需求摘录：**

> 文件职责 目标文件位于 `org.opentcs.strategies.basic.routing.edgeevaluator` 包，职责是比较一个内层 `BoundingBox` 与一个外层 `BoundingBox` 的空间范围，计算内层包围盒在前、后、左、右和顶部相对于外层包围盒超出的尺寸，并返回一个用于查询各方向超出情况的结果对象。

**输入骨架摘录：**

```java
package org.opentcs.strategies.basic.routing.edgeevaluator;

import org.opentcs.data.model.BoundingBox;

public class BoundingBoxProtrusionCheck {
  public BoundingBoxProtrusionCheck();
  public BoundingBoxProtrusion checkProtrusion(BoundingBox inner, BoundingBox outer);
  public static class BoundingBoxProtrusion {
    private final double front;
    private final double back;
    private final double left;
    private final double right;
    private final double top;
    public BoundingBoxProtrusion(double front, double back, double left, double right, double top);
    public boolean protrudesFront();
    public boolean protrudesBack();
    public boolean protrudesLeft();
    public boolean protrudesRight();
    public boolean protrudesTop();
    public boolean protrudesAnywhere();
  }
}
```

**参考语义：** 前、后、左、右差值都按“内盒边界减外盒对应边界”计算；仅正差表示越界，返回对象把非正值规范为零。顶部比较内盒与外盒高度。

**参考实现：**

```java
public BoundingBoxProtrusion checkProtrusion(BoundingBox inner, BoundingBox outer) {
    return new BoundingBoxProtrusion(
        (inner.getLength() / 2.0 - inner.getReferenceOffset().getX()) -
            (outer.getLength() / 2.0 - outer.getReferenceOffset().getX()),
        (inner.getLength() / 2.0 + inner.getReferenceOffset().getX()) -
            (outer.getLength() / 2.0 + outer.getReferenceOffset().getX()),
        (inner.getWidth() / 2.0 - inner.getReferenceOffset().getY()) -
            (outer.getWidth() / 2.0 - outer.getReferenceOffset().getY()),
        (inner.getWidth() / 2.0 + inner.getReferenceOffset().getY()) -
            (outer.getWidth() / 2.0 + outer.getReferenceOffset().getY()),
        inner.getHeight() - outer.getHeight()
    );
  }
```

**基础组输出：**

```java
public BoundingBoxProtrusion checkProtrusion(BoundingBox inner, BoundingBox outer) {
    return new BoundingBoxProtrusion(
        Math.max(0, inner.getLength() / 2.0 - inner.getReferenceOffset().getX()
            - outer.getLength() / 2.0 + outer.getReferenceOffset().getX()),
        Math.max(0, outer.getLength() / 2.0 + outer.getReferenceOffset().getX()
            - inner.getLength() / 2.0 + inner.getReferenceOffset().getX()),
        Math.max(0, inner.getWidth() / 2.0 - inner.getReferenceOffset().getY()
            - outer.getWidth() / 2.0 + outer.getReferenceOffset().getY()),
        Math.max(0, outer.getWidth() / 2.0 + outer.getReferenceOffset().getY()
            - inner.getWidth() / 2.0 + inner.getReferenceOffset().getY()),
        Math.max(0, inner.getHeight() - outer.getHeight()));
  }
```

**SFT 离线预测输出：**

```java
public BoundingBoxProtrusion checkProtrusion(BoundingBox inner, BoundingBox outer) {
    double front = Math.max(
        0,
        inner.getLength() / 2.0 - inner.getReferenceOffset().getX()
            - (outer.getLength() / 2.0 - outer.getReferenceOffset().getX())
    );
    double back = Math.max(
        0,
        inner.getLength() / 2.0 + inner.getReferenceOffset().getX()
            - (outer.getLength() / 2.0 + outer.getReferenceOffset().getX())
    );
    double left = Math.max(
        0,
        inner.getWidth() / 2.0 - inner.getReferenceOffset().getY()
            - (outer.getWidth() / 2.0 - outer.getReferenceOffset().getY())
    );
    double right = Math.max(
        0,
        inner.getWidth() / 2.0 + inner.getReferenceOffset().getY()
            - (outer.getWidth() / 2.0 + outer.getReferenceOffset().getY())
    );
    double top = inner.getHeight() - outer.getHeight();
    return new BoundingBoxProtrusion(front, back, left, right, top);
  }
```

**日志含义：**

基础组 11 条关联命令记录中 10 条失败，多个方向的越界/贴边断言不符；SFT 组 11 条均成功。计数是命令记录，不是 JUnit 方法数。

基础组状态 `failed`，关联命令记录 11 条，其中非零/超时 10 条。代表性命令 `org.opentcs.strategies.basic.routing.edgeevaluator.BoundingBoxProtrusionCheckTest.detectProtrusionFromTheBack, org.opentcs.strategies.basic.routing.edgeevaluator.BoundingBoxProtrusionCheckTest.detectProtrusionFromTheFront` 的日志节选：org.opentest4j.AssertionFailedError at BoundingBoxProtrusionCheckTest.java；org.opentest4j.AssertionFailedError at BoundingBoxProtrusionCheckTest.java。
SFT 离线预测状态 `passed`，关联命令记录 11 条，失败/超时记录 0 条。

## 附录

### Filters：空筛选条件应接受所有对象

**仓库：** openTCS/opentcs；**文件：** `Filters.java`。

**需求摘录：**

> 传入空筛选条件时表示不限制对应条件，返回的谓词接受所有输入对象；传入非空引用或状态时，则仅接受与该条件相等的对象。

**输入骨架摘录：**

```java
package org.opentcs.kernel.extensions.servicewebapi.v1;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.function.Predicate;
import org.opentcs.data.TCSObjectReference;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.OrderSequence;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.data.peripherals.PeripheralJob;

public class Filters {
  private Filters();
  public static Predicate<TransportOrder> transportOrderWithIntendedVehicle(@Nullable TCSObjectReference<Vehicle> vehicleRef);
  public static Predicate<OrderSequence> orderSequenceWithIntendedVehicle(@Nullable TCSObjectReference<Vehicle> vehicleRef);
  public static Predicate<PeripheralJob> peripheralJobWithRelatedVehicle(@Nullable TCSObjectReference<Vehicle> vehicleRef);
  public static Predicate<PeripheralJob> peripheralJobWithRelatedTransportOrder(@Nullable TCSObjectReference<TransportOrder> orderRef);
  public static Predicate<Vehicle> vehicleWithProcState(@Nullable Vehicle.ProcState procState);
}
```

**参考语义：** 当 `vehicleRef` 为 null 时，该工厂返回恒为 true 的谓词；非空时才按订单的 intended vehicle 做相等比较。其他筛选工厂遵循相同空值语义。

**参考实现：**

```java
public static Predicate<TransportOrder> transportOrderWithIntendedVehicle(
      @Nullable
      TCSObjectReference<Vehicle> vehicleRef
  ) {
    return vehicleRef == null
        ? order -> true
        : order -> Objects.equals(vehicleRef, order.getIntendedVehicle());
  }
```

**基础组输出：**

```java
public static Predicate<TransportOrder> transportOrderWithIntendedVehicle(@Nullable TCSObjectReference<Vehicle> vehicleRef) {
    return order -> Objects.equals(order.getIntendedVehicle(), vehicleRef);
  }
```

**SFT 离线预测输出：**

```java
public static Predicate<TransportOrder> transportOrderWithIntendedVehicle(
      @Nullable
      TCSObjectReference<Vehicle> vehicleRef
  ) {
    return vehicleRef == null
        ? order -> true
        : order -> Objects.equals(vehicleRef, order.getIntendedVehicle());
  }
```

**日志含义：**

基础组 10 条关联命令记录中 7 条失败，错误集中在“无需限制”的筛选场景；SFT 显式区分 null 与非 null 条件，10 条命令记录均成功。

基础组状态 `failed`，关联命令记录 10 条，其中非零/超时 7 条。代表性命令 `org.opentcs.kernel.extensions.servicewebapi.v1.FiltersTest.acceptOrderSequenceWithGivenIntendedVehicle, org.opentcs.kernel.extensions.servicewebapi.v1.FiltersTest.acceptOrderSequencesRegardlessOfIntendedVehicle` 的日志节选：java.lang.AssertionError at FiltersTest.java；java.lang.AssertionError at FiltersTest.java。
SFT 离线预测状态 `passed`，关联命令记录 10 条，失败/超时记录 0 条。

### PJsonObject：可选数值读取的缺失值语义

**仓库：** mapfish/mapfish-print；**文件：** `PJsonObject.java`。

**需求摘录：**

> 整数方法会把 Integer.MIN_VALUE 或 Long.MIN_VALUE 的读取结果当成 null，即使底层数据实际就是对应极值。

**输入骨架摘录：**

```java
package org.mapfish.print.wrapper.json;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mapfish.print.wrapper.ObjectMissingException;
import org.mapfish.print.wrapper.PAbstractObject;
import org.mapfish.print.wrapper.PArray;
import org.mapfish.print.wrapper.PElement;
import org.mapfish.print.wrapper.PObject;
public class PJsonObject extends PAbstractObject {
  @Override public final Integer optInt(final String key);
  @Override public final Long optLong(final String key);
  @Override public final Double optDouble(final String key);
  @Override public final Float optFloat(final String key);
  @Override public final Boolean optBool(final String key);
```

**参考语义：** 可选数值读取把 JSONObject 使用的哨兵值转换为 Java null；optInt/optLong 用最小整数哨兵，optDouble 用 NaN。

**参考实现：**

```java
public final Integer optInt(final String key) {
    final int result = this.obj.optInt(key, Integer.MIN_VALUE);
    return result == Integer.MIN_VALUE ? null : result;
  }

public final Long optLong(final String key) {
    final long result = this.obj.optLong(key, Long.MIN_VALUE);
    return result == Long.MIN_VALUE ? null : result;
  }

public final Double optDouble(final String key) {
    double result = this.obj.optDouble(key, Double.NaN);
    if (Double.isNaN(result)) {
      return null;
    }
    return result;
  }
```

**基础组输出：**

```java
@Override public final Integer optInt(final String key) {
    return this.obj.optInt(key, Integer.MIN_VALUE);
  }

@Override public final Long optLong(final String key) {
    return this.obj.optLong(key, Long.MIN_VALUE);
  }

@Override public final Double optDouble(final String key) {
    return this.obj.optDouble(key, Double.NaN);
  }
```

**SFT 离线预测输出：**

```java
public final Integer optInt(final String key) {
    final int val = this.obj.optInt(key, Integer.MIN_VALUE);
    return val == Integer.MIN_VALUE ? null : val;
  }

public final Long optLong(final String key) {
    final long val = this.obj.optLong(key, Long.MIN_VALUE);
    return val == Long.MIN_VALUE ? null : val;
  }

public final Double optDouble(final String key) {
    final double val = this.obj.optDouble(key, Double.NaN);
    return Double.isNaN(val) ? null : val;
  }
```

**日志含义：**

基础组 45 条关联命令记录中 6 条非零退出，其中包括 MapAttributeTest 的异常类型断言；SFT 组 45 条均成功。输出差异符合样本需求规定的可选数值语义，但不据此声称单一方法解释全部 6 条失败。

基础组状态 `failed`，关联命令记录 45 条，其中非零/超时 6 条。代表性命令 `org.mapfish.print.attribute.StringArrayAttributeTest.testWrongType, org.mapfish.print.attribute.map.MapAttributeTest.testMaxDpi` 的日志节选：java.lang.NullPointerException: Cannot invoke "org.json.JSONArray.length()" because "this.array" is null；org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <java.lang.IllegalArgumentException> but was: <java.lang.NullPointerException>。
SFT 离线预测状态 `passed`，关联命令记录 45 条，失败/超时记录 0 条。

### CacheableRequestPolicy：final 局部变量被再次赋值

**仓库：** apache/httpcomponents-client；**文件：** `CacheableRequestPolicy.java`。

**需求摘录：**

> 该策略只允许 HTTP/1.1 请求，并且只允许 GET、HEAD 和 QUERY 方法；请求显式要求 `no-store` 或 `no-cache` 时，即使请求方法和协议版本满足条件，也不得使用缓存。

**输入骨架摘录：**

```java
package org.apache.hc.client5.http.impl.cache;

import org.apache.hc.client5.http.cache.RequestCacheControl;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ProtocolVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CacheableRequestPolicy {
  private static final Logger LOG;
  public boolean canBeServedFromCache(final RequestCacheControl cacheControl, final HttpRequest request);
}
```

**参考语义：** 请求版本为 null 时使用 HttpVersion.DEFAULT；协议版本、方法和缓存控制指令的检查顺序应保持不变。

**参考实现：**

```java
public boolean canBeServedFromCache(final RequestCacheControl cacheControl, final HttpRequest request) {
        final String method = request.getMethod();

        final ProtocolVersion pv = request.getVersion() != null ? request.getVersion() : HttpVersion.DEFAULT;
        if (HttpVersion.HTTP_1_1.compareToVersion(pv) != 0) {
            LOG.debug("non-HTTP/1.1 request cannot be served from cache");
            return false;
        }

        if (!Method.GET.isSame(method) && !Method.HEAD.isSame(method) && !Method.QUERY.isSame(method)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} request cannot be served from cache", method);
            }
            return false;
        }

        if (cacheControl.isNoStore()) {
            LOG.debug("Request with no-store cannot be served from cache");
            return false;
        }
        if (cacheControl.isNoCache()) {
            LOG.debug("Request with no-cache cannot be served from cache");
            return false;
        }
        LOG.debug("Request can be served from cache");
        return true;
    }
```

**基础组输出：**

```java
public boolean canBeServedFromCache(final RequestCacheControl cacheControl, final HttpRequest request) {
    final String method = request.getMethod();
    final ProtocolVersion pv = request.getVersion() != null ? request.getVersion() : HttpVersion.DEFAULT;
    if (HttpVersion.HTTP_1_1.compareToVersion(pv) != 0) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Non-HTTP/1.1 request cannot be served from cache: {}", method);
      }
      return false;
    }
    if (!Method.GET.isSame(method) && !Method.HEAD.isSame(method) && !Method.QUERY.isSame(method)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Method {} cannot be served from cache", method);
      }
      return false;
    }
    if (cacheControl.isNoStore()) {
      LOG.debug("Request contained a no-store directive");
      return false;
    }
    if (cacheControl.isNoCache()) {
      LOG.debug("Request contained a no-cache directive");
      return false;
    }
    LOG.debug("Request can be served from cache");
    return true;
  }
```

**SFT 离线预测输出：**

```java
public boolean canBeServedFromCache(final RequestCacheControl cacheControl, final HttpRequest request) {
        final String method = request.getMethod();
        final ProtocolVersion pv = request.getVersion();
        if (pv == null) {
            pv = HttpVersion.DEFAULT;
        }
        if (HttpVersion.HTTP_1_1.compareToVersion(pv) != 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Non-HTTP/1.1 request cannot be served from cache");
            }
            return false;
        }
        if (!Method.GET.isSame(method) && !Method.HEAD.isSame(method) && !Method.QUERY.isSame(method)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Method {} cannot be served from cache", method);
            }
            return false;
        }
        if (cacheControl.isNoStore()) {
            LOG.debug("Request with no-store directive cannot be served from cache");
            return false;
        }
        if (cacheControl.isNoCache()) {
            LOG.debug("Request with no-cache directive cannot be served from cache");
            return false;
        }
        LOG.debug("Request can be served from cache");
        return true;
    }
```

**日志含义：**

基础组 3 条关联命令记录均成功；SFT 组 3 条均在编译阶段失败，诊断为对 final 局部变量 pv 再赋值。

基础组状态 `passed`，关联命令记录 3 条，失败/超时记录 0 条。
SFT 离线预测状态 `failed`，关联命令记录 3 条，其中非零/超时 3 条。代表性命令 `org.apache.hc.client5.http.impl.cache.TestCacheableRequestPolicy#testIsArbitraryMethodServableFromCache, org.apache.hc.client5.http.impl.cache.TestCacheableRequestPolicy#testIsGetServableFromCache` 的日志节选：error: cannot assign a value to final variable pv；error: cannot assign a value to final variable pv。

### QuotedStringAttribute：不完整输出未进入测试

**仓库：** eclipse/leshan；**文件：** `QuotedStringAttribute.java`。

**需求摘录：**

> 源码明确只对双引号执行自动处理：输出时把值中的双引号转换为反斜杠加双引号，解析时把反斜杠加双引号还原为双引号。

**输入骨架摘录：**

```java
package org.eclipse.leshan.core.link.attributes;

import org.eclipse.leshan.core.parser.StringParser;
import org.eclipse.leshan.core.util.Validate;

public class QuotedStringAttribute extends BaseAttribute {
  public QuotedStringAttribute(String name, String value);
  public QuotedStringAttribute(String name, String value, boolean validate);
  @Override protected void validate();
  @Override public String getValue();
  @Override public String getCoreLinkValue();
  public static <T extends Throwable> String consumeQuotedString(StringParser<T> parser) throws T;
  public static <T extends Throwable> QuotedStringAttribute consumeQuotedString(String parmName, StringParser<T> parser) throws T;
}
```

**参考语义：** 解析时消费一对外层双引号，仅把反斜杠加双引号还原为引号；其他反斜杠序列按原样保留。

**参考实现：**

```java
public static <T extends Throwable> String consumeQuotedString(StringParser<T> parser) throws T {
        parser.consumeChar('\"');
        int start = parser.getPosition();
        while (!parser.nextCharIs('\"')) {
            if (!parser.hasMoreChar()) {
                // missing ending quote
                parser.raiseException("Unable to parse [%s] : missing ending quote to '%s'", parser.getStringToParse(),
                        parser.substring(start - 1, parser.getPosition()));
            }
            // handle escaping
            if (parser.nextCharIs('\\')) {
                // consume \ and escaped char
                parser.consumeNextChar();
                parser.consumeNextChar();
            } else {
                parser.consumeNextChar();
            }
        }
        int end = parser.getPosition();
        parser.consumeChar('\"');

        String unquotedValue = parser.substring(start, end);
        String valueUnescaped = unquotedValue.replace("\\\"", "\"");
        return valueUnescaped;
    }
```

**基础组输出：**

```java
public static <T extends Throwable> String consumeQuotedString(StringParser<T> parser) throws T {
    parser.consumeChar('"');
    int start = parser.getPosition();
    while (!parser.nextCharIs('"')) {
      if (!parser.hasMoreChar()) {
        parser.raiseException(parser.getStringToParse(), parser.substring(start - 1, parser.getPosition()));
      }
      if (parser.nextCharIs('\\')) {
        parser.consumeNextChar();
      }
      parser.consumeNextChar();
    }
    int end = parser.getPosition();
    parser.consumeChar('"');
    return parser.substring(start, end).replace("\\\"", "\"");
  }
```

**SFT 离线预测输出：**

```java
/*******************************************************************************
 * Copyright (c) 2015 Orange.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *     Orange - keep one JSON dependency
 *******************************************************************************/
package org.eclipse.leshan.core.link.attributes;

import org.eclipse.leshan.core.parser.StringParser;
import org.ecl
```

**日志含义：**

基础组一条关联命令成功。SFT 预测虽非空，但 Javadoc 重复后在注释中截断；完整 Java 文件提取器拒绝输出，因此没有测试命令记录。

基础组状态 `passed`，关联命令记录 1 条，失败/超时记录 0 条。
SFT 离线预测状态 `error`，关联命令记录 0 条，失败/超时记录 0 条。

## 源码解释边界

样本通过仅表示报告中该样本关联的测试/构建命令记录全部成功。通过率迁移用于归纳样本结果；案例中的代码差异用于解释具体输出和测试行为，不代表这些差异是总体变化的唯一原因。SFT 离线预测记录标识为 `aixcoder-7b-v2-sft-0826`，本案例不将其归因到 v4 checkpoint。
