# Contract

![](https://bstats.org/signatures/bukkit/Contract.svg)

玩家对玩家合同平台。当前版本提供 SERVICE 委托、WAGER 对赌和 PARTNERSHIP 合作三类合同，重点先保证 Vault 托管资金、接单/接受邀请、提交、确认、裁决、取消退款和管理员仲裁流程正确。

## 依赖

- Vault
- 任意 Vault 经济插件，例如 CMI Economy
- Reputations（可选，只镜像 Contract 信誉增量）

运行时目标为 Paper，不依赖 QuickShop、Lands、RuleGems、Reputations 或数据库驱动。CMI、Essentials/EssentialsX 仅作为可选的 Vault 经济提供者和加载顺序提示，不是 Contract 的硬依赖。安装 Reputations 后，Contract 会通过可选 Bukkit service 注册四个 `Contract:*` 字段并镜像新发生的信誉增量；未安装或桥不可用时，本地 `reputation.yml`、命令与展示照常工作。历史本地值不会自动导入，避免重复计数。Paper 1.21.6+ 使用原生 Dialog 创建/确认界面；较旧 Paper 版本自动回退到库存 GUI 与聊天输入。插件不再打包 AnvilGUI，Adventure 由 Paper 提供。

## 构建

```powershell
.\gradlew.bat :Contract:build
```

从 monorepo 根目录执行。部署产物：

```text
Contract/build/libs/contract-0.1.0.jar
```

同目录的 `Contract-0.1.0-plain.jar` 是未 shade 的原始 jar，不要部署到服务器。需要检查部署 jar 的 Kotlin relocation、字节码版本和 `plugin.yml` 时运行 `.\gradlew.bat :Contract:jarGate`。

## 玩家命令

```text
/contract help
/contract
/contract gui
/contract service <奖金|item> <小时> <标题>|<描述>
/contract service <奖金|item> <小时> --mediator <中间人> <标题>|<描述>
/contract service <奖金|item> <小时> --objective <类型> <目标> <数量> <标题>|<描述>
/contract wager <对方> <押注> <小时> <仲裁者> <标题>|<描述>
/contract partner <对方> <我押注> <对方押注> <小时> <标题>|<描述>
/contract partner <对方> <我押注> <对方押注> <小时> --mediator <中间人> <标题>|<描述>
/contract list [页码]
/contract my
/contract info <id>
/contract accept <id>
/contract submit <id>
/contract claim <id>
/contract approve <id>
/contract resolve <id> <a|b>
/contract mediate <id> <accept|pay|refund|owner|contractor>
/contract cancel <id>
/contract dispute <id> <原因>
```

命令别名：

```text
/ct
```

## GUI 工作台

`/contract`（或 `/contract gui`）打开合同工作台，全程图形界面，普通玩家无需记命令：

- **合同工作台**：首页显示行动收件箱待办数量，入口包含创建合同、行动收件箱、合同大厅、我的合同、帮助和管理员工作台。
- **行动收件箱**：集中显示需要你接受邀请、提交完成、确认付款、确认合作、接受中间人职责或裁决争议的合同。
- **创建合同向导**：先选类型（委托/对赌/合作），标题、玩家名、金额、押注、中间人/仲裁者和期限使用铁砧输入；描述使用聊天文本输入，支持 `cancel` 取消和 `clear` 清空。界面会实时显示描述预览与扣款明细。
- **铁砧签署确认**：创建、接受邀请、接单、确认付款、中间人/仲裁裁决、取消合同、管理员强制付款/退款/关闭等资金动作，都会先进入确认页展示资金后果，再打开铁砧要求输入玩家名或“同意”完成签署。关闭铁砧或签名不符即视为取消，不会产生任何资金动作。
- **管理员工作台**：`contract.admin.view` 可见，按争议/中断结算、进行中、全部分栏检索合同，强制付款/退款/关闭同样需要签署确认。

### 批量任务、模板池与定时发布

- **批次堆叠**：只有带相同显式 `batch-id` 的 SERVICE 子合同才会合并显示；标题、奖励相同但批次不同的合同不会误合并。卡片标题显示 `×总数`，物品堆叠数量显示当前可领取数，并同时展示 `可领取/总数`、`已接取/总数`、`已提交/总数` 和 `已完成/总数`。
- **领取一份**：点击批次卡片后使用“领取一个任务”。服务层会在同步锁内重新选择一份仍为 `OPEN` 的子合同，因此并发点击不会把同一份任务发给两个人；领取后批次可用数量递减。每份子合同仍独立提交、结算、争议和留档。
- **合同模板池**：大厅左下角进入模板池。创建器中可把当前条款保存为私有模板，载入后仍可修改再签署；模板只保存条款，不保存合同 ID、参与进度、托管资金/物品或定时时间。管理员可将模板切换为全服可见。
- **一次性定时发布**：SERVICE 创建器可输入服务器时区的 `yyyy-MM-dd HH:mm`。签署时立即托管全部奖励和创建费，合同先进入 `SCHEDULED`，到点后使用原合同 ID 幂等切换为 `OPEN`；接单截止时间从实际发布时间开始计算。发布前由雇主取消会退回奖励与创建费，且不计入取消信誉。

命令保留为高级/脚本入口，资金逻辑与 GUI 完全共用同一 `ContractService` 路径。

## 权限

默认 `true`（普通玩家开箱即用）：

| 权限 | 作用 |
| :-- | :-- |
| `contract.use` | 打开并浏览合同大厅 |
| `contract.create` | 创建委托/对赌/合作合同 |
| `contract.template.use` | 保存、载入、删除自己的私有模板 |
| `contract.accept` | 接单或接受邀请 |
| `contract.submit` | 提交完成、交付系统目标物品或货币 |
| `contract.claim` | 领取合同暂存的交付物品或奖励物品 |
| `contract.approve` | 确认自己发布的合同并付款 |
| `contract.cancel` | 取消可取消的合同 |
| `contract.dispute` | 发起或撤销争议 |
| `contract.mediate` | 接受中间人职责并裁决自己负责的合同 |

默认 `op`（需要显式授予）：

| 权限 | 解锁什么 |
| :-- | :-- |
| `contract.create.batch` | 创建器里的「发布份数」「重复接取」「重复冷却」，以及命令的 `--count/--repeat/--cooldown` |
| `contract.schedule.create` | SERVICE 创建器的一次性定时发布 |
| `contract.template.manage` | 把模板发布为全服模板、管理他人模板 |
| `contract.template.admin` | 模板库完整管理（含上面两个模板权限） |
| `contract.bypass.limit` | 绕过雇主 `max-open-contracts`、接单方 `max-active-accepted-contracts` 和 `max-scheduled-contracts` 上限 |
| `contract.bypass.fee` | 创建合同不收取 `economy.creation-fee` |
| `contract.bypass.batch-repeat-limit` | 绕过批次的每人一次/冷却规则 |
| `contract.admin.reload` | `/contract admin reload` |
| `contract.admin.settle` | 强制付款/退款/关闭 |
| `contract.admin.view` | 查看全部合同并进入管理工作台 |
| `contract.admin` | 上述管理与绕过权限的父节点 |

### 面向「任务发布者」的授权

要让一个非 OP 玩家全面管理批量任务，最小集合是
`contract.create.batch` + `contract.schedule.create` +（需要共享模板时）`contract.template.manage`。

注意批次的每一份子合同都单独计入雇主的 `limits.max-open-contracts`（默认 3），
所以发布 32 份会直接被上限挡下。两种解法：

- 把 `limits.max-open-contracts` 调高到能容纳单批份数（推荐，影响范围可控）；
- 或授予 `contract.bypass.limit` —— 但同一节点也会解除该玩家的**接单**数量上限。

`contract.bypass.batch-repeat-limit` 默认 `op`，也就是说 OP 自己测试时会绕过每日冷却，
验证重复接取规则请用普通玩家账号。

## 管理命令

```text
/contract all [页码]
/contract admin reload
/contract admin pay <id>
/contract admin refund <id>
/contract admin close <id>
```

`admin close` 只关闭合同并写入事件日志，不移动任何资金。需要资金处理时先使用 `admin pay` 或 `admin refund`，或由管理员在线下核对后再 close。

## 合同类型与状态

- `SERVICE`：传统委托。创建者托管奖金，其他玩家接单，接单者提交完成，创建者 approve 后付款。
- `WAGER`：对赌。甲方创建时托管押注，乙方 accept 时托管同额押注，指定仲裁者用 `/contract resolve <id> <a|b>` 裁决胜方。
- `PARTNERSHIP`：合作。甲方创建时托管自己的押注，乙方 accept 时托管自己的押注，双方都 `/contract approve <id>` 后按规则结算。

SERVICE 和 PARTNERSHIP 可选 `--mediator <中间人>`。中间人不是收款方，也不会经手资金；他必须先 `/contract mediate <id> accept` 接受职责，之后可在合同已生效且未结束时裁决：

SERVICE 支持两种奖励托管：数字金额表示托管 Vault 货币，`item` 表示托管创建者主手整组物品。系统验收目标除事件进度和 `deliver_item` 外，也支持 `deliver_money`，接单者 `/contract submit <id>` 后会提交对应货币并按成功规则结算给雇主。完成后的奖励物品、交付物品，或取消/过期后需要领回的奖励物品，都通过 `/contract claim <id>` 领取。

- `pay` / `contractor`：认定完成或接单方胜，按成功规则付款。
- `refund` / `owner`：认定失效或创建方胜，按失败/退款规则处理。
- PARTNERSHIP 还可用 `a` / `b` 裁定甲方或乙方胜。

WAGER 使用创建时必填的仲裁者和 `/contract resolve <id> <a|b>`，保持原流程。

主要状态：

- `SCHEDULED`：奖励已托管，等待一次性定时公开；不会出现在公开可接取列表。
- `OPEN`：公开 SERVICE，等待接单。
- `PENDING_ACCEPT`：WAGER/PARTNERSHIP 邀请已发出，等待指定对方接受。
- `IN_PROGRESS`：已接单或邀请已接受。
- `SUBMITTED`：SERVICE 已提交完成，等待创建者确认。
- `COMPLETED`、`CANCELLED`、`EXPIRED`、`DISPUTED`：终态或管理员待处理状态。

GUI 合同大厅支持按全部/SERVICE/WAGER/PARTNERSHIP 筛选；“我的合同”会显示与玩家相关的待接受邀请、进行中、争议和历史合同；行动收件箱会进一步筛出需要当前玩家处理的合同。所有资金动作都需要经过确认页和铁砧签署。`/contract admin reload` 会补齐缺失的默认配置和内置语言文件，并关闭旧 GUI 会话、清理创建草稿，避免玩家在重载后继续操作旧数据。

## 资金规则

创建合同时立刻扣除：

- 合同奖金
- 创建费

SERVICE 奖金进入插件托管记录。创建费直接作为经济回收。

WAGER 创建时扣除甲方押注；乙方接受时扣除乙方押注。裁决后胜方获得双方押注扣除完成佣金后的金额，佣金作为经济回收。待接受超时或甲方取消时只退还甲方已托管押注，不会给未接受的乙方付款。

PARTNERSHIP 创建时扣除甲方押注；乙方接受时扣除乙方押注。双方确认成功时各自取回自己的押注扣除完成佣金后的金额；取消、超时或管理员退款按当前状态退回已托管押注。

雇主确认后：

- 接单者获得 `奖金 - 完成佣金`
- 完成佣金作为经济回收

公开合同取消，或接单截止前无人接取而过期：

- 奖金退回雇主
- 创建费不退

接单后合同进入执行状态，不再按创建时的接单截止时间自动过期；执行中的取消、提交、确认和争议按状态规则处理。

进行中合同由接单者取消：

- 奖金退回雇主

进行中或待确认合同由雇主取消：

- 进入争议状态，等待管理员处理

结算付款会先写入 pending settlement/payout 记录，再执行 Vault deposit。重启恢复不会自动重放 deposit；如果发现未完成的 payout 或 settlement，合同会进入争议状态并写入事件日志，等待管理员核对，避免重复付款。

## 配置

主要配置在 `config.yml`：

```yaml
language: zh_CN

economy:
  min-reward: 100.0
  max-reward: 100000.0
  creation-fee: 20.0
  completion-commission-percent: 5.0

limits:
  max-open-contracts: 3
  max-batch-contracts: 64
  max-repeat-cooldown-hours: 8760
  max-templates-per-player: 32
  max-scheduled-contracts: 64
  max-active-accepted-contracts: 3
  max-title-length: 80
  max-description-length: 500
  min-deadline-days: 1
  max-deadline-days: 7

expiry:
  cleanup-interval-minutes: 10
  submitted-auto-approve-hours: 72

retention:
  # 已完成合同保留天数;设为 0 可关闭自动删除
  completed-contract-days: 90
  # 已取消/接单超时关闭合同保留天数;申诉中合同不会自动删除
  closed-contract-days: 30

storage:
  flush-interval-seconds: 30

scheduling:
  max-days-ahead: 30
  scan-interval-seconds: 30

display:
  page-size: 8
  currency-prefix: "$"
```

## 本地化

语言文件位于 `lang/zh_CN.yml` 和 `lang/en_US.yml`，通过 `config.yml` 的 `language` 选择；
缺失的键回退到 `zh_CN`。**所有面向玩家的文本都在语言文件里**，包括 GUI 标题、按钮名与 lore、
签署确认页的资金后果、创建向导的字段名与聊天提示、收件箱/进度标签，以及服务层的全部失败原因。
代码里不保留任何硬编码的界面文案，因此新增一门语言只需要复制一份 yml 并翻译。

所有段都由共享的 `cubex-i18n` 服务解析，因此：某个键在当前语言缺失时会沿
`当前语言 → zh_CN` 回退，而不是显示原始键名；颜色统一用 MiniMessage `<#RRGGBB>`，
占位符统一用 `<name>`。

语言文件结构：

| 段 | 内容 |
| :-- | :-- |
| `status` / `types` / `roles` / `conditions` | 枚举显示名 |
| `objectives` / `objective-targets` / `objective-prompts` | 系统验收目标的名称、字段名、输入提示 |
| `ui` | GUI、向导、确认页、命令输出与服务层失败原因 |
| `terms` | 结算预览里的短语 |
| `messages` | 命令与聊天消息（走 MiniMessage，支持 `<prefix>`） |

`lang-version: 3` 起，`/contract admin reload` 与启动迁移会做两件事：把旧的 `&#RRGGBB`
转成 MiniMessage（包括管理员自己改过的条目，措辞不动），并把 jar 内新增的键补进服务器
已有的语言文件（已存在的条目不覆盖）。

`LanguageParityTest` 在构建时校验三件事：两个语言文件的键集合完全一致、
同一个键在各语言里的占位符集合一致、代码里引用的每个 `ui.*` 键都真实存在。
新增文案时请同时改两个 yml，否则构建会失败。

## 存储

合同数据保存到：

```text
plugins/Contract/contract.yml
plugins/Contract/templates.yml
plugins/Contract/batch-acceptance.yml
plugins/Contract/pending-transactions.yml
plugins/Contract/reputation.yml
plugins/Contract/events.log
```

关闭或完成的合同不会立刻删除。插件会按 `retention` 配置在后台清理已有 `completed-at` 的终态合同；旧数据如果没有结束时间会被保留，避免升级后误删历史记录。

当前版本使用 Bukkit YAML 存储，避免引入 SQLite/MySQL 驱动。后续如果合同数量明显变多，再考虑数据库层。
