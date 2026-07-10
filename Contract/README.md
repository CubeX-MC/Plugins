# Contract

![](https://bstats.org/signatures/bukkit/Contract.svg)

玩家对玩家合同平台。当前版本提供 SERVICE 委托、WAGER 对赌和 PARTNERSHIP 合作三类合同，重点先保证 Vault 托管资金、接单/接受邀请、提交、确认、裁决、取消退款和管理员仲裁流程正确。

## 依赖

- Vault
- 任意 Vault 经济插件，例如 CMI Economy

运行时不依赖 CMI、QuickShop、Lands、RuleGems 或数据库驱动。GUI 铁砧输入使用的 AnvilGUI 已 shade 并重定位打包进插件 jar，无需单独安装；升级 Minecraft/Paper 大版本时需同步确认该内置 AnvilGUI 版本支持目标服端。

## 构建

```bash
mvn package
```

生成文件：

```text
target/contract-0.1.0.jar
```

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

命令保留为高级/脚本入口，资金逻辑与 GUI 完全共用同一 `ContractService` 路径。

## 权限

```text
contract.use
contract.create
contract.accept
contract.submit
contract.approve
contract.cancel
contract.dispute
contract.mediate
contract.admin
contract.admin.reload
contract.admin.settle
contract.admin.view
```

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

display:
  page-size: 8
  currency-prefix: "$"
```

语言文件位于 `lang/zh_CN.yml` 和 `lang/en_US.yml`，可通过 `language` 选择。

## 存储

合同数据保存到：

```text
plugins/Contract/contract.yml
plugins/Contract/pending-transactions.yml
plugins/Contract/events.log
```

关闭或完成的合同不会立刻删除。插件会按 `retention` 配置在后台清理已有 `completed-at` 的终态合同；旧数据如果没有结束时间会被保留，避免升级后误删历史记录。

当前版本使用 Bukkit YAML 存储，避免引入 SQLite/MySQL 驱动。后续如果合同数量明显变多，再考虑数据库层。
