# Regions

Regions 是 CubeX-MC 的可发布场地与小游戏框架。它将 Lands/Cuboid 区域来源、RuleGems 管理权限、可组合 Flag/Effect/Trigger，以及战斗、赛跑和捉迷藏 Mode 统一在带 revision 的发布流程中。

## 运行要求

- Java 21
- Paper 1.21.11 或兼容 Folia 版本
- 可选：Lands（区域和工会来源）、RuleGems（治理侧集成）

Lands 被配置为可选集成；插件不存在或未启用时，Lands Source 不会被宣告为可用。是否要求它在启动时存在由 `integrations.lands.required-for-startup` 控制。

## 构建与自动检查

```text
./gradlew :Regions:test
./gradlew :Regions:build
./gradlew :Regions:shadowJar
```

可部署产物是 `Regions/build/libs/regions-<version>.jar`；不要部署 `*-plain.jar`。根仓库 CI 会单独构建、测试并上传 Regions，同时支持 `regions-v<version>` 标签发布。

## 管理流程

日常场地变更遵循：创建草稿 → GUI/命令编辑 → `validate`/`preview` → 隔离 `trial` → `publish`。运行时只读取已发布 revision；回滚会生成新 revision，不覆盖历史。

常用入口：

- `/regions gui`：管理界面
- `/regions validate <id>`：发布前验证
- `/regions preview <id>`：查看 diff、依赖和重叠解析
- `/regions trial <id>`：仅对操作者应用草稿效果
- `/regions publish <id>`：发布 revision
- `/regions inspect <玩家>`：查看会话与租约
- `/regions cleanup <玩家>`：事故恢复

权限以 `plugin.yml` 为准。常规管理需要治理权限和来源所有权同时满足；`regions.superadmin` 仅用于紧急接管。

## 状态安全

临时效果使用持久化 lease（`effect-escrow.yml`），战斗和回合装备分别使用装备托管文件。正常退出、死亡、reload、停服和下次启动/登录都会尝试恢复。Folia 停服阶段不会提交无法保证执行的实体任务，而是保留托管数据供下次安全恢复。

比赛默认 300 秒超时，可用 `timeout-seconds` 配置。所有延迟任务都绑定具体局实例；旧局计时器不能结束或修改新局。上一局恢复完成前，同一区域不会启动下一局。

## 上线资料

- [架构](docs/architecture.md)
- [兼容性](docs/compatibility.md)
- [自动回归基线](docs/regression-baseline.md)
- [发布检查单](docs/release-checklist.md)
- [真人验收清单](REAL_PLAYER_TEST.md)
