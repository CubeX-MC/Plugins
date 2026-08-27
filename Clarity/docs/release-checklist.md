# Clarity 发布检查单

## 自动门禁

- [ ] `:Clarity:test` 全部通过且无意外 skipped。
- [ ] `:Clarity:clean :Clarity:build :Clarity:jarGate` 通过。
- [ ] `:Clarity:shadowJar --rerun-tasks` 通过。
- [ ] 部署的是 `Clarity/build/libs/Clarity-<version>.jar`，不是 `*-plain.jar`。
- [ ] `jarGate` 报告 Clarity 自有类为 Java 21（major 65）、无未 relocate 的 Kotlin、
      无 kotlin-reflect 实现，并包含 `cubex-core`。

## JAR 人工确认

- [ ] 最终 `plugin.yml` 的版本、主类、`api-version: '1.21'`、命令、别名与 `clarity.use` 正确。
- [ ] bStats service id 仍为 `31800`，后台项目名与发布名称一致。
- [ ] Clarity 不使用 SQLite；JAR 内没有 `org/sqlite/native/**`。
- [ ] Clarity 不使用 Adventure；JAR 内没有额外的 `net/kyori/**` 副本。
- [ ] 最终 JAR 能读取默认 `config.yml` 与 `plugin.yml`。

## 部署前

- [ ] 备份测试服、玩家数据与 `plugins/Clarity`，保留当前黑名单和 dry-run 设置。
- [ ] 记录 Paper/Folia 与 Java 的实际版本；必须使用 Java 21。
- [ ] 清除旧 Clarity JAR，只部署最终 shadow JAR，避免重复加载。
- [ ] 首次启动保持 `dry-run: true` 和 `auto-clean-on-join: false`，确认配置解析及 bStats 无错误。
- [ ] 用 `/clarity player scan` 与 `/clarity item scan` 核对实际残留 id 后，再配置黑名单。
- [ ] 明确本版本是否接受仅英文硬编码消息；若要求 i18n，先完成语言资源再发布。

## 真人验收

- [ ] 完成仓库根 [`REAL_SERVER_TEST.md`](../../REAL_SERVER_TEST.md) 的 Clarity 槽位遍历流程。
- [ ] 在 Paper 1.21+ 用测试玩家验证 scan、sweep、attr/effect purge 与全部物品范围。
- [ ] 验证 `@s`、`@p`、`@r`、`@a`，并确认控制台与玩家执行结果一致。
- [ ] dry-run 不修改任何状态；关闭 dry-run 后只清除显式黑名单命中，绝不触碰 `minecraft` 命名空间。
- [ ] 在真实 Folia 验证进服延迟扫描和命令清理，无跨区域线程错误。
- [ ] 停用/降级前保留配置；本插件直接修改 Bukkit 玩家状态，回滚 JAR 不会自动恢复已清除数据。

所有阻断项修复并记录证据后，才创建 Clarity 首发标签。
