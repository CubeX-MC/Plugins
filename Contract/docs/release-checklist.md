# Contract 发布检查单

## 自动门禁

- [ ] `:Contract:test` 全部通过且无意外 skipped。
- [ ] `:Contract:clean :Contract:build :Contract:jarGate` 通过。
- [ ] `:Contract:shadowJar --rerun-tasks` 通过。
- [ ] 部署的是 `Contract/build/libs/contract-<version>.jar`，不是
      `Contract-<version>-plain.jar`。
- [ ] `jarGate` 报告自有类为 Java 17（major 61）、无未 relocate 的 Kotlin、无
      kotlin-reflect 实现，并包含所需 CubeX 模块。

## JAR 人工确认

- [ ] 最终 `plugin.yml` 的版本、主类、命令、权限、`depend: [Vault]` 与 softdepend 列表正确。
- [ ] bStats service id 仍为 `31491`，后台项目名与发布名称一致。
- [ ] Contract 不使用 SQLite；JAR 内没有 `org/sqlite/native/**`。
- [ ] Paper 提供 Adventure；JAR 内没有 `net/kyori/**`，FoliaLib 位于 Contract 私有 relocation。
- [ ] `config.yml`、`templates.yml`、`lang/zh_CN.yml`、`lang/en_US.yml` 与 `plugin.yml`
      均能从最终 shadow JAR 读取。

## 部署前

- [ ] 备份测试服、经济数据与 `plugins/Contract`，尤其保留 `contract.yml`、
      `pending-transactions.yml`、`events.log` 及其备份。
- [ ] 升级后抽查 `contract.yml` 的可选 `item-claims.<recipient>.<source>`；降级前先清完所有
      非旧式 SERVICE 池可表达的物品领取权，否则旧版本会忽略这些终态领取记录。
- [ ] 记录 Paper、Java、Vault、经济 provider、PlaceholderAPI 和 Reputations 的实际版本。
- [ ] 不把 ALLIANCE 底层模型误报为可用玩家功能；开发数据含 `alliance` 签署记录时，
      不降级到不认识 `PENDING_ACCEPT_MULTI` / `ALL_APPROVE` 的版本，也不绕过异常签署加载保护。
- [ ] 开发测试中的 ALLIANCE 注资日志若有 `funding-phase`，核对成员 UUID、金额和签署操作 ID；
      PREPARED/REFUNDING 不确定窗口需结合经济插件记录人工核对，不直接删日志或降级。
- [ ] 清除旧 Contract JAR，只部署最终 shadow JAR，避免重复加载。
- [ ] 启动日志确认 Vault economy 已连接，且没有 migration、pending transaction、escrow、
      scheduler 或语言资源错误。
- [ ] 分别在无 Reputations/PlaceholderAPI 和安装两者的环境启动，确认可选连接只降级、不阻止 Contract。
- [ ] 执行 `/contract admin reload`，确认配置与双语言 reload 成功且旧 GUI 会话已关闭。

## 真人验收

- [ ] 完成仓库根 [`REAL_SERVER_TEST.md`](../../REAL_SERVER_TEST.md) 中 Contract 的命令、聊天输入、
      GUI 与首发流程。
- [ ] 在 Paper 1.21.6+ 验证 Dialog 创建/确认；在较旧目标 Paper 验证库存 GUI + 聊天回退。
- [ ] 用真实 Vault 完成 SERVICE、WAGER、PARTNERSHIP 的创建、托管、付款、退款、争议和管理员处理。
- [ ] ITEM 结算覆盖成功交付、取消/超时返还、争议裁决、背包满和 claim 存档失败；重启后领取人、
      来源角色和物品数量不变。SALE 另验证命令/GUI 创建确认、确认后换手失败关闭、买家接受、
      双方审批、卖家收款与买家领取。
- [ ] 从 `lang-version: 3` 的服主自定义语言文件升级，确认 SALE 新键补齐且旧措辞不被覆盖；
      中英文各完成一次创建、接受、审批和领取反馈。
- [ ] 从 `lang-version: 4` 升至 5，确认多方待签署标签补齐，原有中英文定制状态文案保留。
- [ ] 从 `lang-version: 5` 升至 6，确认注资/恢复提示补齐并保留自定义文本。
- [ ] 正常停服与一次可控异常终止后，核对 `余额 + 托管` 守恒、pending journal 可解释且没有双付。
- [ ] 在真实 Folia 完成创建、聊天输入、结算、reload 与停服流程，无线程违规。
- [ ] 记录数据格式、回滚版本与恢复步骤；存在未人工核对的 payout/settlement 时不得发布。

所有阻断项修复并记录证据后，才创建 Contract 首发标签。
