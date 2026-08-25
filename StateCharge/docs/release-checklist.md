# StateCharge 发布检查单

## 自动门禁

- [ ] `:modules:cubex-core:test`、`:StateCharge:test` 全部通过且无 skipped。
- [ ] `:StateCharge:build` 通过。
- [ ] `:StateCharge:shadowJar --rerun-tasks` 与 `:StateCharge:jarGate` 通过。
- [ ] 部署的是 `statecharge-<version>.jar`，不是 `plain` JAR。
- [ ] 最终 JAR 包含插件主类、relocate 后的 Kotlin/FoliaLib 与所需 CubeX 模块。

## 部署前

- [ ] 备份测试服、经济数据和 `plugins/StateCharge`，保留 `states.yml` 与 `.bak`。
- [ ] 记录 Paper/Folia、Java、Vault 和经济 provider 的实际版本。
- [ ] 确认 `economy.account` 指向测试账户，并记录测试前双方余额。
- [ ] 启动日志无 migration、storage、Vault、线程或配置解析错误。
- [ ] `/sc admin reload` 返回成功；修改计费/flush 周期后确认新周期已生效。

## 真人验收

- [ ] 完成 [`../REAL_SERVER_TEST.md`](../REAL_SERVER_TEST.md) 的 Paper 流程。
- [ ] 在真实 Folia 完成计费、切换、reload、跨区和重登流程，无线程违规。
- [ ] 与 Regions 交叉开启/关闭 scale 与 flight，两种退出顺序都不覆盖另一方效果。
- [ ] 模拟 Vault provider 抛错/拒付，状态被关闭且无重复扣款。
- [ ] 损坏 `states.yml` 时从 `.bak` 恢复；两者都损坏时 reload 失败并保留运行中状态。
- [ ] 正常停服、重启与一次可控异常终止后，在线时长、累计金额和效果恢复符合预期。

所有阻断项修复并记录证据后，才创建首发标签。
