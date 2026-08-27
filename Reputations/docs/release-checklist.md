# Reputations 发布检查单

## 自动门禁

- [ ] `:Reputations:test` 全部通过且无 skipped。
- [ ] `:Reputations:build` 与 `:Reputations:jarGate` 通过。
- [ ] `:Reputations:shadowJar --rerun-tasks` 通过。
- [ ] 部署的是 `Reputations-<version>.jar`，不是 `*-plain.jar`。
- [ ] 最终 JAR 包含 Reputations 主类、4 个 Java API 类、relocate 后的 Kotlin 与 `cubex-core`。
- [ ] 最终 JAR 不包含 `me/clip/**` 或 `net/kyori/**`；PlaceholderAPI 保持 `compileOnly`。

## JAR 人工确认

- [ ] 最终 `plugin.yml` 的版本、主类、命令、权限与 `softdepend: [PlaceholderAPI]` 正确。
- [ ] bStats 服务 ID 仍为 `31877`，且后台项目与发布名称一致。
- [ ] 本插件不打包 SQLite 原生库；JAR 内没有 `org/sqlite/native/**`。
- [ ] 本插件不使用 Adventure；JAR 内没有额外的 `net/kyori/**` 副本。

## 部署前

- [ ] 备份测试服与 `plugins/Reputations`，尤其是 `reputation-data.yml`。
- [ ] 记录 Paper/Spigot、Java、PlaceholderAPI 与字段提供方插件的实际版本。
- [ ] 清除旧 Reputations JAR，只部署最终 shadow JAR，避免重复加载。
- [ ] 不装 PlaceholderAPI 启动一次，确认 Reputations 独立启用且没有缺类错误。
- [ ] 安装 PlaceholderAPI 启动一次，确认 `/papi list` 出现 `reputations`。

## 真人验收

- [ ] 完成 [`../REAL_SERVER_TEST.md`](../REAL_SERVER_TEST.md) 的命令、权限、GUI、排行榜与 PAPI 流程。
- [ ] 用 Contract 注册真实字段，完成 set/add/reset，并核对 `ReputationChangeEvent` 的前后值与类型。
- [ ] 分别从主线程与异步测试调用更新，异步事件只在回到正确调度域后触碰 Bukkit 状态。
- [ ] 正常停服重启后数值、缓存名和排行榜一致；删除字段提供方后旧值保留但不展示。
- [ ] 可控异常终止后只允许丢失最后一个 flush 周期内的改动，存档仍可重新加载。
- [ ] 在最低支持线 Spigot/Paper 1.18.x 和目标生产版本各完成一次启动与基本命令验证。

所有阻断项修复并记录证据后，才创建 Reputations 首发标签。
