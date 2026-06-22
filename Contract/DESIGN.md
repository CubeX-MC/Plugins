# Contracts 插件机制与设计总结

## 1. 定位

Contracts 是一个玩家对玩家的合同平台。它不是服主发布每日任务的任务插件，而是让玩家自己发布委托、托管奖金、等待其他玩家接单，并在完成后结算报酬。

核心目标：

- 让养老服产生稳定的玩家需求，而不是只依赖服务器任务。
- 让钱从有需求的玩家流向有劳动意愿的玩家。
- 用押金托管、状态机、接单到期处理和管理员仲裁降低欺诈。
- 和现有 CMI / Vault / QuickShop / Lands / RuleGems 生态配合，而不是重做经济系统。

## 2. 核心循环

1. 雇主创建合同，填写标题、描述、类型、奖金、接单截止时间。
2. 插件检查雇主余额，并立即扣除奖金和可能的手续费。
3. 奖金进入托管账户，不再属于雇主，也暂时不属于接单者。
4. 其他玩家在合同板浏览并接单。
5. 接单者完成工作后提交完成。
6. 雇主确认完成，插件把奖金从托管中支付给接单者。
7. 平台收取少量手续费作为经济回收。
8. 双方可评价，形成声望记录。

## 3. 合同状态机

建议状态：

- `DRAFT`：草稿，只存在于创建流程中，不写入正式合同板。
- `OPEN`：已发布，奖金已托管，等待接单。
- `IN_PROGRESS`：已被接单，正在执行。
- `SUBMITTED`：接单者已提交完成，等待雇主确认。
- `COMPLETED`：雇主确认，奖金已支付。
- `CANCELLED`：合同取消，按规则退款或进入仲裁。
- `EXPIRED`：接单前无人接受，按规则自动退款。
- `DISPUTED`：双方产生争议，等待管理员裁决。

关键原则：

- 支付只能发生在 `SUBMITTED -> COMPLETED`。
- 发布合同必须先成功托管奖金。
- 没有托管记录的合同不能进入 `OPEN`。
- 取消和过期必须有明确的资金流向。

## 4. 资金机制

### 4.1 托管

创建合同时：

- 检查雇主余额是否大于等于 `奖金 + 创建费`。
- 扣除奖金，写入托管表。
- 扣除创建费或发布手续费，直接进入系统回收。
- 合同写入数据库并进入 `OPEN`。

如果数据库写入失败，必须把已扣除奖金退回雇主。

### 4.2 付款

雇主确认后：

- 合同从 `SUBMITTED` 进入 `COMPLETED`。
- 托管奖金扣除平台佣金后支付给接单者。
- 佣金不进入任何玩家账户，作为经济回收。

推荐默认：

- 创建费：`$20`
- 最低奖金：`$100`
- 最高奖金：`$100000`
- 完成佣金：`5%`
- 每人最多公开合同：`3`
- 每人最多接单中合同：`3`

### 4.3 退款

取消规则：

- `OPEN` 状态雇主取消：奖金退回雇主，创建费不退。
- `IN_PROGRESS` 状态雇主取消：进入 `DISPUTED`，避免雇主恶意取消。
- `IN_PROGRESS` 状态接单者放弃：奖金退回雇主，接单者声望扣分。
- `SUBMITTED` 状态雇主不确认：超过宽限期后进入 `DISPUTED` 或自动确认。

接单过期规则：

- `OPEN` 到期无人接单：奖金退回雇主。
- `PENDING_ACCEPT` 到期无人接受邀请：已托管资金退回发起方。
- `IN_PROGRESS` 接单后不再按创建时截止时间过期，执行纠纷通过取消或争议处理。
- `SUBMITTED` 不受创建时截止时间影响，可按提交后的自动确认宽限期处理。

终态保留规则：

- `COMPLETED`、`CANCELLED`、`EXPIRED` 会先留在对应历史分栏。
- 超过配置的保留天数后才从合同库清理。
- `DISPUTED` 和缺少结束时间的旧终态数据不自动删除。

## 5. 合同类型

MVP 不应一开始做太复杂的自动验证。先把通用合同流程做稳。

### 5.1 通用委托

适合：

- 建筑
- 跑腿
- 探图
- 清地
- 帮忙搬运
- 红石工程
- 装修

完成方式：

- 接单者点击提交完成。
- 雇主人工确认。
- 有争议时管理员裁决。

### 5.2 物品交付

第二阶段优先做。

机制：

- 雇主创建合同时选择物品和数量。
- 接单者提交时打开交付 GUI。
- 插件检查物品类型、数量、耐久、自定义名称、Lore、NBT 策略。
- 交付物进入雇主领取仓库。
- 检查通过后可自动进入 `SUBMITTED` 或直接 `COMPLETED`。

默认建议不要严格比较全部 NBT，避免普通物品交付过于麻烦。高价值物品可以配置严格比较。

### 5.3 击杀 / 采集 / 挖矿 / 农业

第三阶段再做。

这些类型需要监听大量事件，并处理外挂、刷怪塔、队友代打、世界限制等问题。适合作为可选模块，而不是 MVP 核心。

## 6. GUI 与命令

建议命令：

- `/contracts`：打开合同板。
- `/contract create`：创建合同。
- `/contract my`：我的合同。
- `/contract accept <id>`：接单。
- `/contract submit <id>`：提交完成。
- `/contract approve <id>`：确认完成。
- `/contract cancel <id>`：取消合同。
- `/contract dispute <id>`：发起争议。
- `/contract admin`：管理员面板。

别名：

- `/contract`
- `/contracts`
- `/ct`

GUI 页面：

- 合同大厅：按开放、进行中、待确认、申诉中、已完成、已关闭分栏展示，支持分页。
- 合同卡片：显示阶段、下一步、资金和接单截止，玩家不用进入详情也能判断进度。
- 创建向导：类型、标题、描述、奖金、接单截止时间、地点。
- 合同详情：雇主、接单者、奖金、状态、描述、操作按钮。
- 我的合同：我发布的、我接取的、待确认的、争议中的。
- 管理员仲裁：待处理争议、强制退款、强制付款、关闭合同。

## 7. 权限设计

基础权限：

- `contracts.use`
- `contracts.create`
- `contracts.accept`
- `contracts.submit`
- `contracts.approve`
- `contracts.cancel`
- `contracts.dispute`

限制权限：

- `contracts.limit.open.3`
- `contracts.limit.open.5`
- `contracts.limit.active.3`
- `contracts.limit.active.5`
- `contracts.bypass.fee`
- `contracts.bypass.limit`

管理员权限：

- `contracts.admin`
- `contracts.admin.reload`
- `contracts.admin.dispute`
- `contracts.admin.force-pay`
- `contracts.admin.force-refund`
- `contracts.admin.delete`

## 8. 数据库设计

建议默认 SQLite，支持后续 MySQL。

### 8.1 contracts

字段：

- `id`
- `owner_uuid`
- `contractor_uuid`
- `type`
- `title`
- `description`
- `reward`
- `fee`
- `status`
- `created_at`
- `accepted_at`
- `submitted_at`
- `completed_at`
- `expires_at`
- `world`
- `x`
- `y`
- `z`

### 8.2 escrows

字段：

- `id`
- `contract_id`
- `owner_uuid`
- `contractor_uuid`
- `amount`
- `fee`
- `commission`
- `status`
- `created_at`
- `released_at`
- `refunded_at`

托管状态：

- `HELD`
- `RELEASED`
- `REFUNDED`
- `DISPUTED`

### 8.3 events

记录所有关键操作：

- 创建
- 接单
- 提交
- 确认
- 取消
- 退款
- 支付
- 争议
- 管理员裁决

这张表对查诈骗、误操作和经济问题很重要。

### 8.4 reviews

字段：

- `contract_id`
- `reviewer_uuid`
- `target_uuid`
- `rating`
- `comment`
- `created_at`

## 9. 配置建议

```yaml
economy:
  min-reward: 100
  max-reward: 100000
  creation-fee: 20
  completion-commission-percent: 5.0
  refund-creation-fee: false

limits:
  max-open-contracts: 3
  max-active-accepted-contracts: 3
  min-deadline-hours: 1
  max-deadline-hours: 168

expiry:
  open-refund: true
  in-progress-refund: true
  submitted-auto-approve-hours: 72

disputes:
  enabled: true
  allow-owner-dispute: true
  allow-contractor-dispute: true

display:
  page-size: 8
```

## 10. 与服务器现有系统的配合

### 10.1 CMI / Vault

Contracts 只通过 Vault 做经济交易。当前服务器使用 CMI 经济，Vault 可作为统一接口。

### 10.2 QuickShop

QuickShop 解决玩家商店和物品买卖，Contracts 解决非标准劳动需求。两者互补：

- QuickShop：我要买 64 个钻石。
- Contracts：我要有人帮我清一片山、建一段路、搬一批箱子、整理仓库。

### 10.3 Lands

建筑类合同可以记录坐标和世界。后续可以检查地点是否在雇主领地内，甚至要求雇主给接单者临时 Lands 权限。

第一阶段不建议自动改 Lands 权限，避免权限泄露。只在合同详情里提示雇主需要手动 trust。

### 10.4 RuleGems

RuleGems 的 Prosperity Gem 提供少量受控货币增发，Contracts 提供玩家之间的资金流通。两者组合可以形成健康经济：

- Prosperity Gem：增加少量公共资金。
- Contracts：把钱转化成玩家服务需求。
- QuickShop / Lands upkeep / 合同手续费：回收货币。

## 11. 从 GigHub 得到的教训

GigHub 的方向正确，但不能照搬当前实现。

需要避免的问题：

- 创建合同时只检查余额，不实际冻结奖金。
- 完成提交时就付款，而不是雇主确认后付款。
- deadline 输入小时数，却按 Unix 毫秒时间戳判断。
- autoVerify 只是字段，没有真实事件验证。
- debug 日志过多，会污染服务器控制台。
- 管理命令包含测试数据和清库功能，上正式服前必须严格隔离。

Contracts 的实现应先保证资金状态一致，再做花哨功能。

## 12. MVP 范围

第一版只做这些：

- 创建通用合同。
- 合同发布时托管奖金。
- 合同大厅 GUI。
- 接单。
- 提交完成。
- 雇主确认后付款。
- 取消和接单到期退款。
- 管理员强制退款 / 强制付款。
- 中文语言文件。
- SQLite 存储。
- 事件日志。

第一版暂不做：

- 自动验证击杀。
- 自动验证挖矿。
- 自动验证建筑完成度。
- 跨服合同。
- Discord 推送。
- 复杂声望算法。

## 13. 推荐实现顺序

1. 搭建 Maven Bukkit 插件骨架。
2. 接入 Vault，封装 EconomyService。
3. 建立 SQLite 表结构和 DAO。
4. 实现合同状态机和托管资金服务。
5. 写命令版完整流程，先不做 GUI。
6. 写基础 GUI。
7. 加接单到期清理任务。
8. 加管理员仲裁。
9. 加中文语言文件和 hex 颜色。
10. 做物品交付合同。

## 14. 设计底线

- 所有钱的流动必须先写日志，再改变状态。
- 没有托管记录就不能展示为可接合同。
- 任何异步数据库操作完成后，回到主线程操作 Bukkit API。
- 玩家输入必须限制长度、过滤控制符、避免颜色码滥用。
- 管理员清库、生成测试数据等功能不能默认启用。
- 所有玩家可见颜色尽量使用 16 进制颜色。
