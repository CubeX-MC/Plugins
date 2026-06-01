# Contracts 路线图

按阶段从上到下执行。每完成一项打勾。
当前进展:**阶段 0 全部完成,阶段 A 模型层抽象完成,阶段 B 的 WAGER/PARTNERSHIP 与通用基础设施已完成**;后续从 B.4/C/D 未完成项继续。

---

## 阶段 0 — 上线前修复 (✓ 全部完成)

修复 16 项 P0/P1/P2 问题。详细记录见下方"修复历史"。

---

## 阶段 A — 模型层抽象 (✓ 完成)

把 Contract 从 "owner+contractor+reward" 的硬编码模型抽出来,以支持未来的对赌、合作、联盟等多种合同类型。

**已完成**:
- [x] 新增 `Asset` / `AssetKind`(MONEY/ITEM/LAND_PERMISSION)
- [x] 新增 `Participant` / `ParticipantRole`(11 种角色覆盖各类合同)
- [x] 新增 `PayoutRule` / `PayoutCondition` / `PayoutRecipient`(资金流规则)
- [x] 新增 `ContractType`(7 种类型枚举占位)
- [x] 新增 `ResolutionRule`(5 种完成判定)
- [x] 重写 `Contract`,内部用新模型,保留 `ownerUuid/reward` 等老 API 派生兼容
- [x] 重写 `ContractStorage`,新格式 + 旧格式自动迁移
- [x] 重写 `ContractService.pay/refund`,改为按 `payouts` 规则执行 `executePayouts(condition)`
- [x] 加 10 个新单测覆盖 Asset/Participant/PayoutRule 序列化和应用逻辑
- [x] 旧 contracts.yml 自动迁移(测试通过)

---

## 阶段 B — 加合同类型

按从简单到复杂的顺序,每个新类型既是产品功能,也是对模型抽象的回归验证。
推荐先做 B.1 WAGER,因为它能验证最大的几个抽象点(双方押注、ARBITER、单方通吃)。

### B.0 通用基础设施(为已落地新类型铺垫)

- [x] **命令路由重构(WAGER/PARTNERSHIP 已落地)**
  - SERVICE 使用类型化子命令,并已增加新类型子命令:
    - `/contract service <奖金> <小时> <标题>|<描述>` (SERVICE)
    - `/contract wager <对方> <押金> <小时> <仲裁> <标题>|<描述>`
    - `/contract partner <对方> <我押注> <对方押注> <小时> <标题>|<描述>`
    - `/contract resolve <id> <a|b>`
  - BOUNTY / ALLIANCE 仍是后续阶段,未在 README 承诺为可用命令。
  - 文件: `command/ContractCommand.java`

- [x] **GUI 列表按类型区分图标和过滤器**
  - `ContractGui.materialFor(status)` 已改为 `materialFor(type, status)`
  - 合同大厅/我的合同顶部已加类型筛选按钮(全部 / SERVICE / WAGER / PARTNERSHIP)
  - 文件: `gui/ContractGui.java`

- [x] **`info` 命令显示类型+完整 participants 列表**
  - 已显示 type、participants、押注、arbiter、payout 预览和 partnership 双方确认状态
  - 文件: `command/ContractCommand.java:sendInfo`

- [x] **SERVICE / PARTNERSHIP 可选中间人**
  - `/contract service ... --mediator <玩家>` 和 `/contract partner ... --mediator <玩家>` 可指定第三方中间人
  - 中间人不是资金/物品持有人,只负责在合同生效后接受职责并用 `/contract mediate` 裁决 pay/refund/owner/contractor
  - 文件: `command/ContractCommand.java`,`service/ContractService.java`,`gui/ContractGui.java`

- [x] **`accept` 行为按 type 分支(WAGER/PARTNERSHIP 已落地)**
  - SERVICE:1 个接单者(现状)
  - WAGER:接受 = 押注配对,需要扣款
  - PARTNERSHIP:邀请的对方接受 = 押注 + 进入 IN_PROGRESS
  - BOUNTY / ALLIANCE 留到后续对应阶段
  - 文件: `service/ContractService.accept`
  - 设计备注:当前先用局部分支保持小步可审;类型继续增多时再引入 `ContractTypeHandler`

### B.1 WAGER 对赌(✓ 完成)

**目标**:验证"双方押注 + 玩家仲裁 + 单方通吃"路径。

- [x] **`Contract.createWager(creator, opponent, stake, arbiter, hours, title, desc)`**
  - 2 个 PARTY_A/PARTY_B 参与者,各押 stake
  - arbiter 字段绑定第三方玩家
  - resolutionRule = ARBITER
  - payouts:
    - `DISPUTE_RESOLVED_FOR_OWNER` → PARTY_A 100% A 的押注 + 100% B 的押注(赢家通吃)
    - `DISPUTE_RESOLVED_FOR_CONTRACTOR` → PARTY_B 拿全部
    - `TIMEOUT` → 各退各的
  - **注意**:目前 PayoutRule 只支持单 source。要么改为 `List<source>` 或者拆成两条规则(A win → 从 A 拿一半给 A、从 B 拿全部给 A)
  - 决定:**拆两条规则**更简单,不动模型

- [x] **状态机扩展:`PENDING_ACCEPT` 状态**
  - 当 creator 创建 WAGER,合同进入 `PENDING_ACCEPT` 而不是 OPEN
  - opponent 点 accept = 扣款 + 进入 IN_PROGRESS
  - 超时未接受 = 退 creator 押注,合同 CANCELLED
  - 文件: `model/ContractStatus.java` (加新状态),`service/ContractService`,`storage/ContractStorage`(load 兼容)

- [x] **`/contract resolve <id> <a|b>` 仲裁命令**
  - 只有 arbiter 玩家可以执行
  - 触发对应 `DISPUTE_RESOLVED_FOR_*` payouts
  - 文件: `command/ContractCommand.java`,`service/ContractService.resolveWager`

- [x] **新增测试** (WagerTest 7 个 + ContractStatusTest 新增 1 个)
  - PENDING_ACCEPT 初始状态
  - 双方押注相等
  - A wins payouts 数学验证
  - B wins 镜像
  - TIMEOUT 各退各的
  - FAILURE 只退 A
  - ContractStatus.awaitsAcceptance() 新方法

### B.2 BOUNTY 悬赏 — **延后到 C.X**

B.1 完成后重新评估:BOUNTY 与 SERVICE 在没有 EVENT 自动结算的情况下行为完全一致,只是图标/命名差异。
独立加 BOUNTY type 不验证新抽象维度,纯属换皮。
**决策**:推到 C.X,与 `PayoutCondition.EVENT` + 自动触发机制一起实现。
真正的悬赏:"谁先杀掉 ender dragon 触发 EVENT → 自动 settle SUCCESS → 第一个 claimer 拿走奖励"。

### B.3 PARTNERSHIP 合作(✓ 完成)

**目标**:验证"双方押 + BOTH_APPROVE + 共享池"。

- [x] **`Contract.createPartnership(...)`** — PARTY_A/PARTY_B 各押,resolutionRule=BOTH_APPROVE
  - 共享池(sharedPool)未实现,留待后续
- [x] **状态机:`BOTH_APPROVE` 路径** — metadata `approved-roles` 记录已确认方,集满 2 个触发 SUCCESS settle
- [x] **payouts 规则** — SUCCESS 各退各扣 commission;TIMEOUT/FAILURE 各退各全额;DISPUTE_RESOLVED_FOR_* 给守约方全部
- [x] **`/contract approve <id>` 支持双签** — approve 入口按 type 分支到 approvePartnership
- [x] **新增测试** — PartnershipTest 5 个

### B.4 ALLIANCE 联盟

**目标**:验证"多方参与 + TIMEOUT 守约 + 违约罚"。

- [ ] **`Contract.createAlliance(creator, allies, stakePerAlly, hours, title, desc)`**
  - 多个 ALLY 参与者
  - resolutionRule = TIMEOUT(到期无违约自动结算)
  - payouts:
    - TIMEOUT → 每个 ALLY 拿回自己的押注
    - 单 ALLY 主动取消 → 该 ALLY 押注按 N-1 等分给其他 ALLY,合同进入 CANCELLED

- [ ] **状态机:`PENDING_ACCEPT_MULTI`**
  - 所有 invitee 都接受才进入 IN_PROGRESS
  - 部分未接受到期 → 退已押注的玩家

- [ ] **payouts 模型升级**
  - 现有 PayoutRule 一次只能从一个 source 取
  - 联盟违约罚需要 "N-1 个 source 各拿 1/(N-1) 的违约者押注"
  - 方案 A:加 `SourceSelector`(VIOLATOR / NON_VIOLATORS / ALL_PARTICIPANTS)
  - 方案 B:动态生成 payouts (在违约时按当时状态构造一组规则)
  - **推荐 B**,避免模型膨胀

---

## 阶段 C — 复杂资产 + 边界类型

### C.1 ITEM Asset 落地

- [ ] **`Asset.item()` 当前只存字符串引用,需要真序列化 ItemStack**
  - 用 Bukkit `ItemStack.serialize()` / `deserialize()` 写到 yaml
  - 或者用 PDC + base64 编码

- [ ] **托管物品箱**
  - 物品押注后存到哪里?玩家点击"押注"时把背包物品收进插件的虚拟箱
  - 用 `plugins/Contracts/escrow.yml` 存储,key = contract id, value = list of ItemStack

- [ ] **SALE 合同类型**
  - BUYER 押 MONEY,SELLER 押 ITEMS
  - BOTH_APPROVE 后交换:BUYER 收 ITEMS,SELLER 收 MONEY

### C.2 LAND_PERMISSION Asset(Lands 集成)

- [ ] **soft-depend Lands**
- [ ] **押注 = 临时授予对方 trust;完成时撤销**
- [ ] **风险**:Lands API 不稳,合同生命周期内 Lands 配置可能变化,需要降级策略

### C.3 LOAN 借贷类型

- [ ] **特殊:initial-transfer**(creator 创建时直接把钱 transfer 给 debtor,而非 escrow)
- [ ] **抵押物**(可选):debtor 押 ITEMS 作为抵押
- [ ] **到期自动判决**:
  - 还款成功 → 退还抵押物
  - 还款失败 → 抵押物给 creditor

### C.4 RECURRING 模式(租赁)

- [ ] **复杂特性,推到最后**
- [ ] **方案**:不在 Contract 内部加 schedule 字段,改为"父合同生成子合同"模式
- [ ] **应用场景**:租领地、订阅服务

---

## 阶段 D — 用户体验

- [ ] **GUI 创建向导**
  - 选类型(SERVICE/WAGER/BOUNTY/PARTNERSHIP/ALLIANCE) → 按类型填字段
  - 替代纯命令行创建

- [x] **合同详情按类型显示**
  - WAGER:显示双方押注、仲裁者、裁决入口
  - PARTNERSHIP:显示双方押注、双方确认入口和 info 结算预览

- [x] **类型筛选**
  - GUI 顶部加按钮:全部/服务/对赌/合作
  - 联盟/悬赏筛选随对应类型落地后再开放

- [ ] **PlaceholderAPI**
  - `%contracts_open_count%` / `%contracts_my_active%` / `%contracts_my_pending_wager%`

- [ ] **bStats 集成**

- [x] **多语言支持**
  - 已有 lang/en_US.yml,lang/zh_CN.yml
  - config.yml 已加 `language: zh_CN`

- [x] **reload 后强制关闭旧 GUI session**

- [ ] **声望/评价系统**(DESIGN §8.4)
  - 合同完成后双方可互评
  - reputation 影响 max-open-contracts 上限

---

## 跨阶段不变量(底线)

每个阶段都必须维持:
1. **资金状态一致** — 任何时刻 `余额 + 托管 = 之前余额`,经过宕机 / reload 后仍成立
2. **审计完整** — 所有资金流动先 append 到 events.log,再改 contract 状态
3. **零行为变更兼容** — 阶段 A 引入抽象时,SERVICE 合同的玩家可见行为完全一致;阶段 B/C 引入新类型时,老合同读写不破坏
4. **新类型必须有单测** — 资金分配规则、状态机转换、超时处理至少各一条测试用例

---

## 修复历史(阶段 0)

### P0 Blocker(资金/数据安全)
- [x] **#1 description 被 title 顶替** — 命令加 `|<描述>` 分隔
- [x] **#2 资金原子性破口** — pending-transactions.yml + onEnable 回滚
- [x] **#3 全量同步写 yaml 阻塞主线程** — 脏标记 + 30s 异步刷盘 + 资金事务仍 sync
- [x] **#4 GUI 争议原因写死** — 拆按钮 + 聊天监听器收原因

### P1 High(安全/审计/规范)
- [x] **#5 Tab Complete 泄露所有合同 ID** — 按权限过滤
- [x] **#6 缺少独立事件审计** — events.log append-only
- [x] **#7 ContractStatus 硬编码中文** — 走 LanguageManager
- [x] **#8 玩家改名后名字不更新** — load 时按 UUID resolve
- [x] **#9 金钱用 double** — BigDecimal,Vault 边界转 double
- [x] **#10 零测试覆盖** — 26 个 JUnit 5 单测

### P2 Medium
- [x] **#11 commission 无上限** — clamp [0,100]
- [x] **#12 README vs config 字段名不一致** — 统一 `display.page-size`
- [x] **#13 DESIGN 缺失配置项** — 实现 refund-creation-fee-on-cancel、disputes.allow-*
- [x] **#14 toLowerCase 没指定 Locale** — 全部加 Locale.ROOT
- [x] **#15 events 用 `|` 分隔不结构化** — 新增 ContractEvent record
- [x] **#16 info 命令对所有玩家无差别开放** — 非相关方隐藏 contractor 和 disputeReason

### P3 Low(部分推到阶段 D)
- [x] reload 后强制关闭旧 GUI session → 阶段 D 已完成
- [ ] PlaceholderAPI 钩子 → 阶段 D
- [ ] bStats → 阶段 D
- [x] README 加路线图 → 本 PLAN.md 替代
