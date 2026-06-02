# CubeX Scheduler Design Draft

本文件是 FoliaLib 统一调度的设计稿,仅做 API 与迁移方案评审。本轮不实现 `modules/cubex-scheduler`,不修改任何插件代码,不引入其它可选模块。

## 0. 设计约束

- 新增独立可选模块 `modules/cubex-scheduler`;依赖 `modules/cubex-core` 与 FoliaLib,但 `cubex-core` 继续保持无第三方依赖。
- Java 实现,维持当前 1.18 编译基线;不使用 Kotlin,不要求 1.16 兼容。
- 目标是零行为变更:同步/异步、delay/period、global/region/entity、teleport、取消、disable 清理、Folia 检测语义都必须承接现有行为。
- 先抽取真实重复,不做通用并发框架。`cubex-scheduler` 只封装 Bukkit/Paper/Folia 调度边界,不接管业务生命周期、命令、配置、数据库。
- FoliaLib 是第三方依赖,后续随各插件 shade 进 jar 并 relocate;sqlite 规则不受影响。

## 1. 现状清单

扫描范围:

- `EcoBalancer/src/main/java/org/cubexmc/ecobalancer/utils/SchedulerUtils.java` 382 行
- `RuleGems/src/main/java/org/cubexmc/utils/SchedulerUtil.java` 308 行
- `Metro/src/main/java/org/cubexmc/metro/util/SchedulerUtil.java` 323 行
- `Railway/src/main/java/org/cubexmc/metro/util/SchedulerUtil.java` 342 行
- `RuleGems/src/test/java/org/cubexmc/utils/SchedulerUtilTest.java`

### 1.1 能力矩阵

| 能力 | EcoBalancer | RuleGems | Metro | Railway |
|---|---|---|---|---|
| Folia 检测 | `isFolia()` 反射检测 `RegionizedServer` | `isFolia()` 静态缓存 | 使用 `VersionUtil.isFolia()`,无 public accessor | `isFolia()` |
| Global sync | `globalRun(plugin, task, delay, period)`;`runTask*` 别名 | `globalRun(...)` | `globalRun(...)` | `globalRun(...)` |
| Entity sync | `entityRun(...)` | `entityRun(...)` | `entityRun(...)` | `entityRun(...)` |
| Region/location sync | `regionRun(...)` | `regionRun(...)` | `regionRun(...)` | `regionRun(...)` |
| Async | `asyncRun(...)`;`runTaskAsync/runTaskLaterAsync` | `asyncRun(...)` | `asyncRun(...)` | `asyncRun(...)` |
| 单任务取消 | `cancelTask(Object)` | `cancelTask(Object)` | `cancelTask(Object)` | `cancelTask(Object)` |
| 插件级取消 | `cancelAllTasks(plugin)` + 自维护 tracked handles | 无 | 无 | 无 |
| Teleport | `safeTeleport(plugin, Player, Location)` void | `safeTeleport(plugin, Player, Location)` void | `teleportEntity(Entity, Location)` future | `teleportEntity(Entity, Location)` future |
| Tick 计数 | 无 | 无 | 无 | `getCurrentTick()` + `ensureTickCounter(plugin)` |
| Folia fallback 警告 | 无 | 无 | `warnUnsafeBukkitFallbackIfNeeded` | `warnUnsafeBukkitFallbackIfNeeded` |

### 1.2 共同调度语义

四份 util 都在做同一组核心事情:

- Folia 上通过反射调用 global/entity/region/async scheduler。
- Bukkit/Spigot/Paper 非 Folia 上回落到 Bukkit scheduler。
- `delay` 单位为 tick;Folia async 旧实现把 tick 转成 `delay * 50L` 毫秒。
- `period < 0` 表示一次性任务;正数 period 表示 repeating task。现有调用点基本使用 `-1L` 表示 one-shot,没有发现以 `0L` period 表达 repeating 的稳定需求。
- 返回值是原生 handle 或 `BukkitTask`;调用方用 `Object` 保存,再交给 `cancelTask(Object)`。

差异必须保留:

- EcoBalancer 与 RuleGems 在 Bukkit 主线程且 `delay == 0 && period < 0` 时会立即 inline 执行,并返回 `null`。RuleGems 已有单测固定此语义: `SchedulerUtilTest.globalRunExecutesInlineWhenPrimaryThreadNoDelay`。
- Metro/Railway 的 Bukkit fallback 对 `delay == 0` one-shot 仍走 `runTask`,不会 inline。
- EcoBalancer 会跟踪所有 handle,`cancelAllTasks(plugin)` 既调用 Bukkit `cancelTasks(plugin)`,也取消 tracked Folia handles;其 reload 路径依赖这个能力。
- Railway 额外依赖 tick counter:优先反射 `Bukkit.getCurrentTick()`,否则用每 tick 自增 fallback。
- Metro/Railway 在 Folia reflection 失败时只 warn 一次,然后回落 Bukkit scheduler。

### 1.3 调用点清单

EcoBalancer 调用点最多,以全局 sync/async 和插件级取消为主:

- `EcoBalancer.java:180`,`:1196` 定时清理与经济快照。
- `EcoBalancer.java:295` reload 时 `SchedulerUtils.cancelAllTasks(this)`。
- `EcoBalancer.java:671`,`:1171`,`:1176`,`:1387` 异步写税务/数据库/扫描。
- `EcoBalancer.java:741`,`:840`,`:955`,`:969`,`:1023`,`:1039`,`:1328`,`:1353`,`:1383` 同步回主线程或延迟重试。
- `commands/*` 多处 async 查询后 sync 回主线程渲染消息,例如 `CheckRecordCommand.java:77`,`:98`,`:138`;`TaxCommand.java:743`,`:745`,`:779`,`:781`。
- `metrics/Metrics.java:105` bStats submit hook 使用 `runTask`。
- `UtilCommand.java:68` 暴露 cancelAll/reload 类操作。

RuleGems 覆盖 entity/region/teleport,并有现成单测:

- `RuleGems.java:125`,`:135` 启动周期性保存/检查任务。
- `GemManager.java:96`,`:225`,`:295`,`:678`,`:682` flush、异步保存、实体/区域任务。
- `GemPlacementManager.java:93`,`:124`,`:140`,`:237`,`:289`,`:321`,`:335`,`:345`,`:391`,`:531`,`:534`,`:577`,`:658`,`:704`,`:708`,`:718`,`:720`,`:743` 区域方块修改、逃逸/倒计时任务、取消。
- `GemConsumeListener.java:155`,`:209`,`:294` 玩家实体线程任务。
- GUI/命令 teleport: `RulersGUI.java:88`, `RulerAppointeesGUI.java:89`, `TpSubCommand.java:54`,`:59`, `GemsGUI.java:78`,`:84`。
- `AppointFeature.java:874`,`:893`,`:905` refresh task handle 和 Folia 分支。
- `SchedulerUtilTest` 当前验证 inline one-shot 与 delayed Bukkit scheduler。

Metro 调用点集中在列车、Portal、生命周期和玩家提示:

- `Metro.java:100` 命令异步执行入口。
- `ScheduledTaskLifecycle.java:104`,`:109` 与 `MapIntegrationLifecycle.java:182`,`:187` 封装 repeating lifecycle。
- `TrainScheduler.java:23`,`:35`,`:40`;`TrainDisplayController.java:307`;`TrainMovementTask.java:94` entity task 与取消。
- `BedrockArrivalSync.java:58`,`:71`,`:80`;`BedrockMountSync.java:28`,`:33`,`:52`,`:58`,`:64` entity/teleport/region 串联。
- `PortalManager.java:314`,`:321`,`:346`;`VehicleListener.java:74`,`:86`;`SoundUtil.java:59` region task。
- `PlayerInteractListener.java:68`,`:163`,`:386`,`:491`;`PlayerMoveListener.java:233`,`:260`,`:316`,`:384`,`:398`,`:423`,`:471`,`:481`,`:490`,`:493` handle 取消和玩家实体提示。

Railway 与 Metro 同源,但额外广泛依赖 tick:

- `Metro.java:106`,`:192` async command 与 `ensureTickCounter(this)`。
- 与 Metro 类似的 lifecycle/train/portal/player/vehicle 调用: `ScheduledTaskLifecycle.java:104`,`:109`;`MapIntegrationLifecycle.java:182`,`:187`;`TrainScheduler.java:23`,`:35`,`:40`;`TrainMovementTask.java:93`。
- Railway-only tick 使用: `VehicleListener.java:421`, `RailwayPlaceholders.java:95`, `TrainDisplayController.java:311`, `LineService.java:57`,`:76`,`:168`,`:232`,`:235`, `LineServiceManager.java:49`,`:50`,`:63`,`:109`,`:134`, `LocalDispatchStrategy.java:181`, `VirtualTrain.java:373`,`:424`。
- Railway-only Folia 分支/物理: `TrainInstance.java:218`,`:358`,`:828`;`MinecartPhysicsUtil.java:39`,`:46`,`:52`,`:53`,`:54`,`:61`,`:67`,`:68`;`LeashCoupler.java:74`;`EntityModelController.java:140`。

## 2. FoliaLib 覆盖核对

选用版本与坐标:

```kotlin
repositories {
    maven("https://repo.tcoded.com/releases")
}

dependencies {
    implementation("com.tcoded:FoliaLib:0.5.1")
}
```

依据: FoliaLib 官方 README 说明该库用于兼容 Spigot/Paper/Folia 调度,示例包含 `runNextTick`, `runLater`, `runTimer`, `runAsync`, `runTimerAsync`, `runAtEntity`, `runAtLocation`, `teleportAsync`, `cancelAllTasks`,以及 `isFolia/isPaper/isSpigot`;同时要求 relocate `com.tcoded.folialib` 并避免 minimize 排除错误。Maven metadata 显示 `com.tcoded:FoliaLib:0.5.1` 已发布。

| 现有能力 | FoliaLib 覆盖 | 需要 CubeX 包装的点 |
|---|---|---|
| Folia/Paper/Spigot 检测 | 覆盖: `isFolia/isPaper/isSpigot` | 暴露 `CubexScheduler.isFolia()` 以替代现有 util |
| Global sync one-shot/delay/timer | 覆盖: global scheduler, tick API | 为 Eco/RuleGems 保留 Bukkit 主线程 inline 返回 null 的 legacy 行为 |
| Async one-shot/delay/timer | 覆盖: async scheduler, tick/TimeUnit 示例 | 公共 API 统一使用 tick;legacy adapter 保持现有 `void asyncRun(...)` |
| Entity task | 覆盖: `runAtEntity*` | 保留 retired/entity invalid 时的 no-op 或日志语义,实现期逐项确认 |
| Region/location task | 覆盖: `runAtLocation*` | 名称统一为 location,legacy 保留 `regionRun` |
| 单任务取消 | 覆盖: wrapped task cancel | 需要 `CubexTask` 包装 native handle,并让 `cancelTask(Object)` 接受旧 handle/新 handle |
| 插件级取消 | 覆盖: FoliaLib scheduler `cancelAllTasks()` | EcoBalancer reload 需要插件级 registry;不能只依赖 Bukkit disable 自动取消 |
| Teleport | 覆盖: `teleportAsync(entity, location, cause?)` | Eco/RuleGems 的 `safeTeleport(Player)` void 与 Metro/Railway future 都要保留 |
| Railway tick | 不属于 FoliaLib 核心调度 | 在 scheduler 模块提供 `currentTick()` 与 `ensureTickCounter()` 兼容工具 |
| Folia reflection fallback warning | FoliaLib 内部处理平台差异 | Metro/Railway 的一次性 warning 可作为 legacy adapter 行为保留或迁移时删除前需 review |

缺口结论:

- FoliaLib 能覆盖 4 份 util 的 scheduler/teleport 主体,足以替换 300+ 行手写反射。
- 真正需要 CubeX 自己补的是"兼容现有边界":inline one-shot、`Object` handle、EcoBalancer cancelAll registry、Railway tick counter、旧 teleport 返回类型。
- `cubex-scheduler` 不应把 FoliaLib 原 API 原样泄漏给插件,否则后续 FoliaLib API 变动会扩散到 8 个插件。

## 3. cubex-scheduler API 契约草案

包名建议: `org.cubexmc.scheduler`。

### 3.1 核心类型

```java
package org.cubexmc.scheduler;

import org.cubexmc.core.CubexPlugin;

public final class CubexScheduler {
    public static CubexScheduler bindTo(CubexPlugin plugin);

    public CubexPlugin plugin();
    public boolean isFolia();
    public boolean isPaper();
    public boolean isSpigot();

    public CubexTask runGlobal(Runnable task);
    public CubexTask runGlobalLater(Runnable task, long delayTicks);
    public CubexTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    public CubexTask runAsync(Runnable task);
    public CubexTask runAsyncLater(Runnable task, long delayTicks);
    public CubexTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

    public CubexTask runAtEntity(org.bukkit.entity.Entity entity, Runnable task);
    public CubexTask runAtEntityLater(org.bukkit.entity.Entity entity, Runnable task, long delayTicks);
    public CubexTask runAtEntityTimer(org.bukkit.entity.Entity entity, Runnable task, long delayTicks, long periodTicks);

    public CubexTask runAtLocation(org.bukkit.Location location, Runnable task);
    public CubexTask runAtLocationLater(org.bukkit.Location location, Runnable task, long delayTicks);
    public CubexTask runAtLocationTimer(org.bukkit.Location location, Runnable task, long delayTicks, long periodTicks);

    public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(
            org.bukkit.entity.Entity entity,
            org.bukkit.Location location);

    public long currentTick();
    public void ensureTickCounter();
    public void cancelAll();
}
```

职责:

- `bindTo(plugin)` 创建并绑定到 `CubexPlugin` 的 `Terminable` 生命周期;插件 disable 时自动 `cancelAll()`。
- 所有 `run*` 方法返回 `CubexTask`;默认注册到该 scheduler 的 active registry,取消或完成后标记 inactive。
- 新 API 使用显式方法区分 one-shot/delay/timer,不再让新代码传 `period = -1L`。
- `currentTick/ensureTickCounter` 只为 Railway 的真实需求保留,不放进 `cubex-core`。

```java
package org.cubexmc.scheduler;

import org.cubexmc.core.Terminable;

public interface CubexTask extends Terminable {
    void cancel();
    boolean isCancelled();
    Object nativeHandle();

    @Override
    default void close() {
        cancel();
    }
}
```

职责:

- `CubexTask` 是统一取消句柄,隐藏 BukkitTask/FoliaLib WrappedTask。
- `nativeHandle()` 只用于迁移期诊断与 legacy adapter,新业务代码不依赖它。

### 3.2 兼容适配层

为做到逐插件替换且零行为变更,建议提供一个迁移期 legacy facade。它不是鼓励新代码使用,而是承接现有 4 份 util 的 public surface。

```java
package org.cubexmc.scheduler;

public final class LegacySchedulerAdapter {
    public enum BukkitImmediateMode {
        INLINE_WHEN_PRIMARY_THREAD,
        ALWAYS_SCHEDULE
    }

    public static LegacySchedulerAdapter ecoBalancer(CubexScheduler scheduler);
    public static LegacySchedulerAdapter ruleGems(CubexScheduler scheduler);
    public static LegacySchedulerAdapter metro(CubexScheduler scheduler);
    public static LegacySchedulerAdapter railway(CubexScheduler scheduler);

    public boolean isFolia();

    public Object globalRun(Runnable task, long delayTicks, long periodTicks);
    public Object entityRun(org.bukkit.entity.Entity entity, Runnable task, long delayTicks, long periodTicks);
    public Object regionRun(org.bukkit.Location location, Runnable task, long delayTicks, long periodTicks);
    public void asyncRun(Runnable task, long delayTicks);

    public void cancelTask(Object taskHandle);
    public void cancelAllTasks();

    public void safeTeleport(org.bukkit.entity.Player player, org.bukkit.Location destination);
    public java.util.concurrent.CompletableFuture<Boolean> teleportEntity(
            org.bukkit.entity.Entity entity,
            org.bukkit.Location destination);

    public long getCurrentTick();
    public void ensureTickCounter();
}
```

职责:

- `ecoBalancer(...)` 打开 task registry + cancelAll 行为,并保留 inline 主线程 one-shot。
- `ruleGems(...)` 保留 inline 主线程 one-shot,以现有 `SchedulerUtilTest` 为验收基线。
- `metro(...)` 和 `railway(...)` 使用 `ALWAYS_SCHEDULE`,避免把 Metro/Railway 的 Bukkit fallback 变成 inline。
- `railway(...)` 启用 `getCurrentTick/ensureTickCounter`;其它 adapter 调用这两个方法可以抛 `UnsupportedOperationException` 或返回 Bukkit tick,实现前需 review。
- 迁移完成后,各插件可以先保留本地 `SchedulerUtil` 类,内部委托 adapter;这样调用点可以分批替换。

## 4. 试点与映射

建议试点: RuleGems。

理由:

- 有现成 `SchedulerUtilTest`,能直接固定最危险的 inline/null handle 行为。
- 调用面覆盖 global/entity/region/async/cancel/teleport,但没有 EcoBalancer 的 reload cancelAll 与 Railway tick counter,试点复杂度适中。
- RuleGems 的 `SchedulerUtil` 无 task registry,替换风险比 EcoBalancer 低。

RuleGems 映射:

| 现有调用 | 新门面/adapter | 行为保持点 |
|---|---|---|
| `SchedulerUtil.isFolia()` | `adapter.isFolia()` | 返回 FoliaLib 检测结果 |
| `globalRun(plugin, task, 0L, -1L)` | `adapter.globalRun(task, 0L, -1L)` | Bukkit 主线程 inline 执行并返回 `null` |
| `globalRun(plugin, task, delay, -1L)` | `adapter.globalRun(task, delay, -1L)` | delayed one-shot 仍返回可取消 handle |
| `globalRun(plugin, task, delay, period)` | `adapter.globalRun(task, delay, period)` | repeating task 仍可由 `cancelTask` 取消 |
| `entityRun(plugin, player, task, delay, period)` | `adapter.entityRun(player, task, delay, period)` | Folia entity scheduler;Bukkit fallback 保留旧语义 |
| `regionRun(plugin, location, task, delay, period)` | `adapter.regionRun(location, task, delay, period)` | Folia location scheduler;Bukkit fallback 保留旧语义 |
| `asyncRun(plugin, task, delay)` | `adapter.asyncRun(task, delay)` | 返回 void;delay 单位 tick |
| `cancelTask(handle)` | `adapter.cancelTask(handle)` | 接受 old/new handle;`null` no-op |
| `safeTeleport(plugin, player, location)` | `adapter.safeTeleport(player, location)` | void API,优先 async teleport,必要时调度到实体/主线程 |

RuleGems 验收建议:

- 保留并迁移现有两个单测: inline one-shot 返回 `null`;delayed task 调用 Bukkit scheduler。
- 新增最小单测: `cancelTask(null)` no-op;repeating handle 取消;`asyncRun` delay tick 传递。
- 构建验收仍比较 jar 内容:除 `org/cubexmc/scheduler/**` 与 relocated FoliaLib 外,plugin.yml/命令/权限/SQLite/Metrics ID 不变。

推广顺序:

1. RuleGems:用单测锁住 adapter 行为。
2. EcoBalancer:验证 `cancelAllTasks(plugin)`、reload 重排任务、file/database 清理不受影响。
3. Metro:验证 `ALWAYS_SCHEDULE`、lifecycle shutdown、列车/Portal/Bedrock teleport。
4. Railway:最后处理 tick counter、VirtualTrain/LineService ETA、实体模型/物理工具。

## 5. 风险与取舍

### 5.1 Folia 语义差异

- FoliaLib 的 global scheduler 仍不适合玩家/方块操作;调用点必须继续按 global/entity/location 分类,不能为了减少 API 数量把所有 sync task 统一成 global。
- EcoBalancer/RuleGems 的 inline one-shot 是可观察行为,尤其 RuleGems 已有测试;不能直接替换成 FoliaLib `runNextTick`。
- Entity retired callback、实体失效、chunk/region 不可用时的行为需要实现期逐项验证。现有代码多为 no-op 或 fine/warn 级日志,不应升级成异常。
- Metro/Railway 现有 fallback warning 属于诊断行为;如果 FoliaLib 正常覆盖后不再触发,应在试点验收中确认管理员日志可接受。

### 5.2 Relocate 策略

- FoliaLib 官方要求 relocate `com.tcoded.folialib`,避免与其它插件冲突。因此每个引入 `cubex-scheduler` 的插件应把 FoliaLib relocate 到各自命名空间,例如 `org.cubexmc.rulegems.libs.folialib`、`org.cubexmc.metro.libs.folialib`。
- `org.cubexmc.scheduler` 是 CubeX 自有代码。为了与当前 `cubex-core` 试点一致,默认不 relocate;风险是多个 CubeX 插件同服但 scheduler 版本不同会有 classpath 冲突。可选策略是在每个插件也 relocate 自有 optional module,但这会偏离 core 既有策略,需要 review 后统一决定。
- FoliaLib 不应被 `minimize` 移除;现有 shadow 配置若使用 include 白名单,必须显式 include `project(":modules:cubex-scheduler")` 与 FoliaLib。

### 5.3 1.18 编译基线

- FoliaLib README 声称面向较老版本也做兼容,并通过包装避免直接依赖 Folia API;但实现前仍需在 `modules/cubex-scheduler` 上验证 Java target 与当前 Bukkit/Paper compileOnly 组合。
- `teleportAsync` 在 Paper/Spigot/Folia 的返回与调度时机可能不同;Metro/Railway 依赖 `CompletableFuture<Boolean>`,RuleGems/EcoBalancer 依赖 fire-and-forget,需分别测。
- Railway `getCurrentTick()` 不是纯 scheduler 功能,但它是消除 Railway 手写 util 的必要兼容点。先放 scheduler 可选模块,不下沉 core。

### 5.4 过度封装边界

- 不抽象业务层"列车任务/经济任务/宝石任务";只封装调度执行位置与取消。
- 不替换 `ScheduledTaskLifecycle`、`MapIntegrationLifecycle` 等已有 lifecycle 类;它们可以改为依赖 `CubexScheduler` 或 legacy adapter。
- 不在本阶段实现 config/i18n/database/command 可选模块。

## 6. 设计结论

`modules/cubex-scheduler` 值得做,但应分两层:

1. `CubexScheduler` + `CubexTask`:面向未来的清晰 Java API,显式区分 global/async/entity/location 和 one-shot/delay/timer,任务自动绑定 `CubexPlugin` 生命周期。
2. `LegacySchedulerAdapter`:迁移期承接 4 份现有 `SchedulerUtil` 的细微行为差异,保证每个插件可以先把本地 util 变成委托层,再逐步清理调用点。

第一轮实现建议只覆盖 RuleGems 试点所需 API 与单测,确认 FoliaLib 覆盖度和 relocate 策略后,再扩展 EcoBalancer cancelAll、Metro/Railway lifecycle、Railway tick counter。

## 7. 参考来源

- FoliaLib 官方仓库与 README: https://github.com/TechnicallyCoded/FoliaLib
- FoliaLib Maven artifact: https://mvnrepository.com/artifact/com.tcoded/FoliaLib/0.5.1

## 8. Review 结论(API 把关人已确认,实现以此为准)

**总体:方向通过。** 两层(CubexScheduler/CubexTask 稳定门面 + 迁移期 adapter)+ RuleGems 试点 + FoliaLib 覆盖核对都对路。以下为必须落实的收紧项:

- **A. 不要在共享模块里硬编码插件名工厂**:去掉 `LegacySchedulerAdapter.ecoBalancer()/ruleGems()/metro()/railway()` 这种命名工厂——共享模块不应认识具体插件。改为暴露**通用配置开关**:`immediateMode`(已有的 `INLINE_WHEN_PRIMARY_THREAD | ALWAYS_SCHEDULE` 枚举)、`trackTasksForCancelAll`(布尔,仅 EcoBalancer 需要)、tick 计数能力(仅 Railway)。各插件在**自己**那侧用这些开关构造/包装(例如把各自本地 `SchedulerUtil` 改成委托一个按需配置的 CubexScheduler/adapter)。这样 quirk 留在插件、共享模块保持干净。
- **B. 自取消重复任务必须支持**:确认是否有插件的重复任务在任务体内部自取消(很可能存在,如 RuleGems `GemPlacementManager` 的倒计时/逃逸任务、各种 countdown)。若有,门面除 `Runnable` 外还要提供把任务句柄传入任务体的重载(如 `runGlobalTimer(Consumer<CubexTask> task, ...)`),否则 Runnable-only API 无法表达"运行中 cancel 自己"。实现前先核对 4 插件的重复任务调用点。
- **C. relocate 决策(定案,解你 §5.2 的开放问题)**:**FoliaLib 必须 relocate**(官方要求、避免冲突,成本低)到各插件 `org.cubexmc.<plugin>.libs.folialib`;**`org.cubexmc.scheduler`/`core` 不 relocate**——Bukkit 每插件独立 ClassLoader 天然隔离,与 cubex-core 现状一致,无需 relocate。sqlite 规则不变、绝不 relocate sqlite。
- **D. LegacySchedulerAdapter 是临时迁移脚手架**:目标终态是各插件直接用 `CubexScheduler`、adapter 删除。新代码不得用 adapter;迁移完成后清理。文档/注释标明其临时性。
- **E. FoliaLib 版本**:实现时确认 `com.tcoded:FoliaLib` 能从 `https://repo.tcoded.com/releases` 解析,钉一个当时的稳定版(0.5.1 或更新),并验证与 1.18 compileOnly + JDK17 target 组合可编译。

**保真红线(实现+验收必须守)**:inline one-shot 返回 null(RuleGems 单测为基线)、`Object` handle 兼容、EcoBalancer `cancelAllTasks` registry、Railway tick counter、teleport 两种返回类型(void safeTeleport / future)、Metro/Railway 的 ALWAYS_SCHEDULE(不 inline)。

**第一轮实现范围**:只做 RuleGems 试点所需的门面 + 配置开关 + adapter 子集 + 单测;EcoBalancer cancelAll / Metro-Railway lifecycle / Railway tick 等留到各自接入轮。**先停下让我 review FoliaLib 实测覆盖与 relocate 产物**,再推广。
