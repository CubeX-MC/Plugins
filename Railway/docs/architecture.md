# Railway 架构

> 源码包沿用 `org.cubexmc.metro`、主类 `org.cubexmc.metro.Metro`——与 Metro 同名是**有意保留**的
> （见根 `AGENTS.md`「已定决策」），两者不能同时安装。

## 插件骨架

`Metro` 继承 `cubex-core` 的 `CubexPlugin`，所有启停都挂在它的 `TerminableRegistry` 上：

- 构建用 Gradle + `cubex-kotlin-plugin` 约定，内嵌模式：`cubex-core` / `-config` / `-i18n` /
  `-scheduler` / `-spatial` / `-gui` / `-economy` shade 并 relocate 进 `railway-<version>.jar`。
- 配置读取集中在 `ConfigFacade`；Railway 独有的调度 / 物理键由 `Metro` 上的属性读取
  （新旧键名双读，旧键名来自 1.0.x）。
- 配置与语言迁移走 `cubex-config` 的迁移框架（`update/MetroMigrations`、`DataFileUpdater`）。
- 语言：`LanguageManager` 是共享 `I18nService` 的薄封装。查找走 i18n 的语言链
  （配置语言 → `zh_CN` → `en_US` → 其余内置语言），磁盘文件缺的键由 jar 内同语言文件兜底；
  渲染仍交给 `MetroTextRenderer`（兼容语言文件里的 `&` 颜色码和 `{name}` 占位符）。

## 数据与持久化

| Store | 文件 | 说明 |
|---|---|---|
| `LineManager` | `lines.yml` | 线路、停靠顺序、票价规则、服务参数、路由点 |
| `StopManager` | `stops.yml` | 停靠区（`cubex-spatial` Octree 按世界索引）、换乘、标题 |
| `PortalManager` | `portals.yml` | 矿车传送门 |

三个 store 都实现 `Reloadable` + `Terminable`，在 `enablePlugin()` 里直接 `bind(store)`：

- 修改只置 dirty；自动保存任务（`ScheduledTaskLifecycle`，每 1200 tick）把快照交给 `SaveCoordinator` 异步写（版本化快照 +
  临时文件原子替换，旧快照不会覆盖新快照）。
- `forceSaveSync()` 同步写；写盘最终失败时 `SaveCoordinator.saveNow` 抛出，store **保持 dirty**，
  `hasUnsavedChanges()` 据此报告。
- 关服时 `TerminableRegistry` 倒序关闭：先停运行时（列车、显示、地图、监听器），再依次 flush
  三个 store，然后 `SaveCoordinator.flushAll()` 等待剩余异步写，最后打印停用消息。

## 重载（`/rw reload`）

`Metro.reloadRailway()` 是一条命名的 `ReloadChain`（`ReloadFailurePolicy.ABORT`），返回
`ReloadReport`，命令层据此告诉服主失败在哪一段：

```
flush-data → default-files → config-migrations → config
  → data-migrations* → lines* → stops* → portals*
  → rail-protection → language → line-services → entity-models → map-integrations
```

带 `*` 的阶段用 `addIf` **以 flush 成功为门**：写盘失败时从磁盘重读会把只在内存里的修改覆盖掉，
所以这几段被跳过（日志列出跳过的阶段）。任何一段抛错，后续阶段都不再执行。

## 运行时

- **线路服务**：`LineServiceManager` 为每条启用服务的线路建一个 `LineService`，用一个全局心跳驱动。
  `service.mode` 决定派车策略：
  - `local`（默认）：`LocalDispatchStrategy`，列车以 `VirtualTrainPool` 中的虚拟列车运行，
    只在有玩家需求的站点附近实体化（`SpawnMode` 决定实体化位置）；
  - `global`：`GlobalDispatchStrategy`，按发车间隔（headway）持续派出实体列车。
- **列车**：`TrainInstance` + `TrainConsist`（多节编组）+ `TrainNavigator`（站间导航、到站判定）。
  `BlockSectionManager` 做区间占用，防止同一区间进入两列车。
- **物理**：`TrainPhysicsEngine` 按线路 `TrainControlMode` 选择——`KINEMATIC`（默认，领头车运动学
  控制 + 跟随车）、`REACTIVE`、`LEASHED`；装有 TrainCarts 时经 `TrainCartsBridge` 反射对接。
- **显示**：`ScoreboardManager`（scoreboard-library）、`TrainDisplayController`（标题 / ActionBar）、
  `EntityModelController`（可选的实体外观）。
- **行程时间**：`TravelTimeEstimator` 按实际运行样本估计区间用时，供 ETA 与派车使用。
- **交互**：Cloud 注解命令（`command.newcmd`，根命令 `/rw`），不可用时回退到 Bukkit 命令注册；
  GUI 基于 `cubex-gui`；监听器在 `lifecycle/ListenerRegistration` 统一注册。
- **集成**：Vault（经 `cubex-economy`，无 owner 线路的票款走 `economy.account`）、BlueMap /
  Dynmap / Squaremap、PlaceholderAPI。缺席时各自降级，不影响启用。

## 调度约定

`cubex-scheduler` 的 `CubexScheduler` 经 FoliaLib 适配 Paper/Bukkit 与 Folia；尚未迁移的
`SchedulerUtil` 调用委派给 `LegacySchedulerAdapter`：

- global：插件级工作（自动保存协调、延迟地图刷新、线路服务心跳）；
- entity：读写玩家、矿车等实体；
- region：按位置读写世界 / 方块（生成矿车、传送门目的地、铁轨检查）；
- async：只做文件 I/O 或对已生成快照的序列化，不碰任何 Bukkit 世界状态。

聊天输入回调复用插件级 `CubexScheduler`，在玩家的 entity scheduler 执行。

`SchedulerUtil` / `LegacySchedulerAdapter` 向 `CubexScheduler` 原生 API 的收敛是非阻塞待办
（根 `PLAN.md` §5.7），不要为此制造大 diff。

## 质量门

- `.\gradlew.bat :Railway:build`：编译 + 单测 + shadowJar；
- `.\gradlew.bat :Railway:jarGate`：部署 jar 门禁（Kotlin relocate、共享模块 relocate、字节码版本）。
