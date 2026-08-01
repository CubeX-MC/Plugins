# Regions 架构

## 数据流

`RegionSource` 解析外部或 Cuboid 几何；`RegionRegistry` 保存运行时已发布定义；`RegionPublishingService` 管理 draft、revision、preview、publish 和 rollback。`RegionDetectionService` 在实体调度器上检测玩家所在区域，并交给 `RegionOverlapResolver` 选择主 Mode、Flag、Effect 和 Trigger 来源。

检测发生在每次跨方块移动上，因此按来源分组处理：已发布集合只在 `RegionStorage.publishedRevision()` 变化时重建，来源可用性每次查询只判定一次，每个来源通过 `RegionSource.containing` 一次解析整批引用。`LandsRegionSource` 借此把同一位置的多个 Lands 场地折叠成一次反射解析。

`RegionSessionService` 维护玩家会话并协调 Mode 与 Effect。Flag 由监听器在事件发生时查询；Trigger 通过 Action/Condition 注册表执行；所有配置在发布前由 `RegionValidationService` 和 capability schema 校验。

`RegionTrigger` 的每个枚举项都必须有运行时触发点：`RegionsPlugin.verifyCapabilityCatalog` 在启动时比对枚举与已注册的 TRIGGER descriptor，不一致直接拒绝启用，避免出现「能保存、能校验、永不触发」的配置。

## 状态所有权

- `ScopedEffectService` 是临时属性、药水、飞行、发光和隐身抑制的唯一所有者。每次应用都会记录原值和 scope；lease 原子写入 `effect-escrow.yml`。一次解析出的整组 Effect 合并为一次 escrow 写入，任一失败则整组回滚，玩家不会停留在半应用状态。
- 没有可取消事件的 `deny` Flag 由 `RegionOverlapResolver` 合成对应 Effect 再交给 `ScopedEffectService` 托管：`fly: deny` 合成 `allow_flight=false`，`vanish: deny` 合成 `invisibility_suppression`。这类合成 Effect 遵循与 Flag 相同的豁免（`regions.bypass.flags`），且不与创造/旁观模式的飞行争夺控制权。`pvp`、`item_drop`、`item_pickup`、`commands` 由监听器取消事件，不需要 lease。
- `CombatModeService` 与 `RoundModeService` 在修改装备前把快照原子写入各自 escrow。死亡、退出、强制结束、reload、停服后重启/登录都可恢复。
- `RaceModeService`、`RoundModeService` 和 `CombatModeService` 的任务闭包持有具体 state 实例。任务执行时重新核对实例，避免旧任务污染新局。
- 模式结束时区域进入 ending 状态；在线玩家的结束恢复完成后才允许下一局。

## 线程模型

玩家/实体状态只在 `CubexScheduler.runAtEntity` 或已知安全的 Paper 主线程路径修改。全局计时器只做 state 判定和结束编排。Paper 正常停服在当前主线程同步恢复；Folia 停服不创建无法保证运行的实体任务，而是保留持久化 lease/escrow 供下次启用恢复。

## 故障模型

磁盘写入采用临时文件加原子替换（不支持时安全降级）。持久化失败会回滚对应内存变更或玩家变更。Effect 组合只在整组成功后缓存签名，失败组会清理并在下一次刷新重试。审计保存发布、强制操作、模式结束和比赛结果等关键事件。

