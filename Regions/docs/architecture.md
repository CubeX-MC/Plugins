# Regions 架构

## 数据流

`RegionSource` 解析外部或 Cuboid 几何；`RegionRegistry` 保存运行时已发布定义；`RegionPublishingService` 管理 draft、revision、preview、publish 和 rollback。`RegionDetectionService` 在实体调度器上检测玩家所在区域，并交给 `RegionOverlapResolver` 选择主 Mode、Flag、Effect 和 Trigger 来源。

`RegionSessionService` 维护玩家会话并协调 Mode 与 Effect。Flag 由监听器在事件发生时查询；Trigger 通过 Action/Condition 注册表执行；所有配置在发布前由 `RegionValidationService` 和 capability schema 校验。

## 状态所有权

- `ScopedEffectService` 是临时属性、药水、飞行、发光和隐身抑制的唯一所有者。每次应用都会记录原值和 scope；lease 原子写入 `effect-escrow.yml`。
- `CombatModeService` 与 `RoundModeService` 在修改装备前把快照原子写入各自 escrow。死亡、退出、强制结束、reload、停服后重启/登录都可恢复。
- `RaceModeService`、`RoundModeService` 和 `CombatModeService` 的任务闭包持有具体 state 实例。任务执行时重新核对实例，避免旧任务污染新局。
- 模式结束时区域进入 ending 状态；在线玩家的结束恢复完成后才允许下一局。

## 线程模型

玩家/实体状态只在 `CubexScheduler.runAtEntity` 或已知安全的 Paper 主线程路径修改。全局计时器只做 state 判定和结束编排。Paper 正常停服在当前主线程同步恢复；Folia 停服不创建无法保证运行的实体任务，而是保留持久化 lease/escrow 供下次启用恢复。

## 故障模型

磁盘写入采用临时文件加原子替换（不支持时安全降级）。持久化失败会回滚对应内存变更或玩家变更。Effect 组合只在整组成功后缓存签名，失败组会清理并在下一次刷新重试。审计保存发布、强制操作、模式结束和比赛结果等关键事件。

