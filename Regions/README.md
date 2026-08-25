# Regions

Regions 是 CubeX-MC 的可发布场地与小游戏框架。它将 Lands/Cuboid 区域来源、RuleGems 管理权限、可组合 Flag/Effect/Trigger，以及战斗、赛跑和捉迷藏 Mode 统一在带 revision 的发布流程中。

## 定位

外部领地插件负责"哪里是区域、谁拥有区域"；Regions 负责"这个区域在服务器规则中**变成什么场地**"。

它不重新实现领地、选区和所有权，而是把 Lands、Cuboid（以及未来的 Residence、WorldGuard）统一成
可配置的 Region，再允许 RuleGems 的统治者为这些区域安装玩法、规则、效果和 IF/THEN 行为。

日常场地管理者不是普通玩家，也不是仅凭领地主身份就能操作的人，而是**同时满足**两个条件的场地主：
持有 `regions.admin`（由 RuleGems 统治者身份授予）**且**是该 Region 所绑定外部区域的实际主人。
服务器管理员只负责紧急接管与事故恢复，走独立的 `regions.superadmin`。

**不做什么**（避免误装）：

- 不是领地插件，不提供圈地与所有权本身。
- 不做脚本语言；复杂玩法写成 Mode，而不是塞进 YAML。
- 未实现或校验不通过的能力**不会**在 GUI 里伪装成可用（fail closed）。

## 运行要求

- Java 21
- Paper 1.21.11 或兼容 Folia 版本
- 可选：Lands（区域和工会来源）、RuleGems（治理侧集成）、Contract（WAGER 奖励托管）

Lands 被配置为可选集成；插件不存在或未启用时，Lands Source 不会被宣告为可用。是否要求它在启动时存在由 `integrations.lands.required-for-startup` 控制。

Contract 同样只是可选连接。`dual_pvp` 和 `union_war` 可在 Mode 中设置 `reward-source: contract` 与 `reward-contract: <WAGER id>`；发布及开赛前会确认该 WAGER 已由双方接受且仍在进行。Contract 缺席或状态不符时只阻止这个有奖励的场地发布/开赛，Regions 其他场地照常运行。

## 构建与自动检查

```text
./gradlew :Regions:test
./gradlew :Regions:build
./gradlew :Regions:shadowJar
```

可部署产物是 `Regions/build/libs/regions-<version>.jar`；不要部署 `*-plain.jar`。根仓库 CI 会单独构建、测试并上传 Regions，同时支持 `regions-v<version>` 标签发布。

## 管理流程

日常场地变更遵循：创建草稿 → GUI/命令编辑 → `validate`/`preview` → 隔离 `trial` → `publish`。运行时只读取已发布 revision；回滚会生成新 revision，不覆盖历史。

已有场地也可以在详情页点击“应用模板”重新选择预设。确认后，模板会整体替换草稿中的 Mode、Flags、Effects 与 Triggers，不会把上一个模板的提醒或效果带过去；Region ID、名称、来源、所有权、优先级和版本历史保持不变。重新预览并发布前，运行态不会变化。

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

Contract 奖励操作另存于 `reward-funding.yml`。一局比赛的 lock、自然胜者 settle、强制结束/reload/崩溃恢复 refund 共用同一个 transaction operation id，只有持有该锁的交易才能终结资金。无法确认的部分结算保留 lease 并要求人工复核，不会生成第二次付款。删除该文件会破坏恢复链，禁止把它当作清理手段。

比赛默认 300 秒超时，可用 `timeout-seconds` 配置。所有延迟任务都绑定具体局实例；旧局计时器不能结束或修改新局。上一局恢复完成前，同一区域不会启动下一局。



## 已知边界

- 尚未公开发布；当前数据格式将作为首个公开版本基线，内部开发期的旧格式不在兼容范围内。
- 未知或未实现的 Capability / Condition 一律**校验失败**而不是默认放行——这是有意的安全默认。
- 校验器与第三方依赖的错误正文目前仍是英文常量，尚未全部拆成翻译键。
- race / hide-and-seek / 赞助 / 多人分成的**真实资金结算**尚未实现（见 `PLAN.md` §5.2 阶段 D）。

## 相关文档

- 待办与路线：仓库根 [`PLAN.md`](../PLAN.md)
- [架构](docs/architecture.md)
- [兼容性](docs/compatibility.md)
- [自动回归基线](docs/regression-baseline.md)
- [发布检查单](docs/release-checklist.md)
- [真人验收清单](REAL_PLAYER_TEST.md)
