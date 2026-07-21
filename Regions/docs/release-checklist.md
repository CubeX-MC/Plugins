# Regions 发布检查单

## 自动门禁

- [ ] `:Regions:test` 全部通过，无 skipped。
- [ ] `:Regions:build` 通过。
- [ ] `:Regions:shadowJar --rerun-tasks` 通过。
- [ ] `regions-<version>.jar` 存在，且包含 Regions 主类、Kotlin runtime、Cubex 模块与 relocation 后的 FoliaLib。
- [ ] CI 的 Regions matrix job 与总 JAR artifact 均成功。
- [ ] 配置、模板、语言文件和 `plugin.yml` 能从最终 shadow JAR 读取。

## 部署前

- [ ] 备份测试服世界、玩家数据和 `plugins/Regions`。
- [ ] 记录 Lands、RuleGems、Paper/Folia 和 Java 的实际版本。
- [ ] 只部署 `regions-<version>.jar`，清除同目录旧 Regions JAR，避免重复加载。
- [ ] 启动后确认没有 schema、Source、registry、线程或 escrow 恢复错误。
- [ ] 执行 `/regions validate`，检查 Lands Source 与已发布 revision。

## 真人验收

- [ ] 完成 `REAL_PLAYER_TEST.md` 的 Paper 全量流程。
- [ ] 在真实 Folia 完成关键线程与恢复流程。
- [ ] 完成一次正常停服恢复和一次可控异常终止恢复。
- [ ] 完成 dual/union、race、hide-and-seek 各一轮并检查审计。
- [ ] 验证失败记录包含 Region id、revision、角色、日志和恢复结果。

所有阻断项修复并回归后，才使用 `regions-v<version>` 标签创建发布。
