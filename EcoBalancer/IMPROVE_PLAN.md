# EcoBalancer 改进计划

本文档基于 EcoBalancer 当前代码与 QuickTax 的设计对比，聚焦三项最值得优先落地的改进：

1. 补全免税与欠款策略
2. 抽出统一的 `TaxRunService`
3. 建立税款账本并暴露 PlaceholderAPI

目标不是把 QuickTax 的实现照搬进来，而是吸收它在服务器日常运营、玩家可见性和安全执行流程上的优点，同时保留 EcoBalancer 已有的策略系统、批处理、SQLite 记录、经济指标与 Folia 适配能力。

## 1. 补全免税与欠款策略

### 背景

EcoBalancer 的 `config.yml` 已经有 `tax-exempt-permission`，`EcoBalancer.checkBalance(...)` 中也保留了 `// Exempt check` 注释，但目前实际扣税流程没有真正执行免税判断。QuickTax 的优点是把免税权限按征税方式拆分，并明确处理余额不足时的行为。

### 目标

- 支持全局免税权限。
- 支持策略级免税权限。
- 支持按执行类型区分免税权限。
- 支持余额不足时的明确处理模式。
- 所有跳过、扣到 0、负债等结果都能被记录和报告。

### 配置设计

建议在主配置或策略配置中增加：

```yaml
tax-exempt:
  enabled: true
  global-permission: "ecobalancer.exempt"
  policy-permission-prefix: "ecobalancer.exempt.policy"
  operation-permission-prefix: "ecobalancer.exempt.operation"

debt-mode: skip
# skip: 余额不足时不扣款
# drain: 最多扣到 0
# allow-negative: 允许扣成负数

debt-commands:
  - "broadcast &e%player% &c余额不足，无法支付本次税款。"
```

策略文件可增加：

```yaml
settings:
  exempt-permission: "ecobalancer.exempt.policy.inactive_tax"
  debt-mode: inherit
```

### 实现步骤

1. 新增税收上下文对象

   建议新增 `TaxContext`，包含：

   - `operationId`
   - `policyName`
   - `operationType`
   - `currentTime`
   - `isBatchRun`
   - `criteria`

2. 新增扣税决策对象

   建议新增 `TaxDecision`：

   - `oldBalance`
   - `requestedDeduction`
   - `actualDeduction`
   - `newBalance`
   - `result`
   - `reason`

   `result` 可包括：

   - `TAXED`
   - `EXEMPT`
   - `INSUFFICIENT_BALANCE_SKIPPED`
   - `DRAINED_TO_ZERO`
   - `NEGATIVE_BALANCE_FIXED`
   - `ZERO_DEDUCTION`
   - `SKIPPED_ONLINE`
   - `SKIPPED_INACTIVE_DAYS`

3. 在 `checkBalance(...)` 中补全免税判断

   判断顺序建议：

   - 税款账户自身跳过
   - 玩家不存在或无经济账户跳过
   - 全局免税权限
   - 策略级免税权限
   - 操作类型免税权限
   - `only-offline` 判断
   - 活跃天数判断
   - 税额计算与欠款策略

4. 实现欠款策略

   将当前 `applyDeduction(...)` 的简单 `withdrawPlayer(player, deduction)` 改为：

   - `skip`: 如果 `balance < deduction`，实际扣款为 0
   - `drain`: 如果 `balance < deduction`，实际扣款为 `max(0, balance)`
   - `allow-negative`: 执行完整扣款

   注意：不同 Vault 经济实现对负数余额支持不一致。`allow-negative` 需要在文档中明确风险，并在代码中记录 Vault 返回结果。

5. 扩展记录表

   当前 `records` 只记录余额和扣款。建议迁移增加字段：

   - `policy_name`
   - `operation_type`
   - `result`
   - `reason`
   - `requested_deduction`
   - `actual_deduction`

6. 更新命令与报告

   - `/eb report` 显示跳过人数、免税人数、余额不足人数。
   - `/eb checkrecord` 显示每个玩家的 `result` 和 `reason`。
   - `/eb tax show` 显示策略的免税权限和欠款模式。

### 验收标准

- 拥有 `ecobalancer.exempt` 的玩家不会被扣款。
- 策略级免税权限只影响对应策略。
- 三种 `debt-mode` 行为可通过手动执行稳定复现。
- `/eb report` 能区分“没扣款是因为免税、余额不足、活跃度不满足，还是税额为 0”。

## 2. 抽出统一的 TaxRunService

### 背景

EcoBalancer 当前的 `checkAll(...)`、`executePolicy(...)`、`checkPlayer(...)` 中有重复的批处理、operation 创建、影响快照、进度消息和收尾逻辑。QuickTax 虽然实现较旧，但它有一个关键优点：运行期间会阻止重复收税任务。

### 目标

- 统一所有税收执行入口。
- 防止定时任务和手动任务重叠。
- 提供运行状态、进度、当前策略、开始时间。
- 为后续取消任务、暂停任务、查看下次执行时间打基础。
- 让 `routine=false` 的策略不被自动调度执行。

### 核心设计

新增服务类：

```java
org.cubexmc.ecobalancer.tax.TaxRunService
```

建议职责：

- 创建 `operation_id`
- 解析目标玩家
- 计算 before snapshot
- 按批次执行扣税
- 保存 records
- 保存 impact
- 输出进度
- 维护运行状态
- 管理运行互斥

建议新增对象：

```java
TaxRunRequest
TaxRunState
TaxRunSummary
TaxRunProgress
```

### 运行状态

`TaxRunState` 建议包含：

- `running`
- `operationId`
- `policyName`
- `startedAt`
- `totalPlayers`
- `processedPlayers`
- `affectedPlayers`
- `totalDeducted`
- `trigger`
- `senderName`

`trigger` 可包括：

- `MANUAL_CHECK_ALL`
- `MANUAL_POLICY_EXECUTE`
- `MANUAL_PLAYER`
- `SCHEDULED`
- `GUI`

### 实现步骤

1. 新增 `TaxRunService`

   在 `EcoBalancer.onEnable()` 初始化，并提供 getter。

2. 将执行入口迁移到服务层

   当前入口保留，但内部委托：

   - `checkAll(sender)` -> `taxRunService.run(TaxRunRequest.checkAll(...))`
   - `executePolicy(sender, policyName)` -> `taxRunService.run(TaxRunRequest.policy(...))`
   - `checkPlayer(sender, playerName)` -> `taxRunService.run(TaxRunRequest.singlePlayer(...))`

3. 抽出单玩家扣税逻辑

   建议将 `checkBalance(...)` 拆成：

   - `TaxDecision calculateDecision(player, policy, context)`
   - `void applyDecision(player, decision, context)`
   - `void recordDecision(player, decision, context)`

4. 加运行互斥

   使用 `AtomicBoolean` 或 `ReentrantLock` 保证同一时间只有一个税收运行。

   如果已有任务在跑：

   - 手动命令返回“已有税收任务运行中”
   - 定时任务记录日志并跳过本轮

5. 增加状态命令

   建议新增：

   - `/eb tax status`
   - `/eb tax cancel`

   第一阶段可以只做 `status`，`cancel` 等服务稳定后再加。

6. 修正 routine 调度语义

   当前 `TaxPolicy.routine` 已存在，但自动调度未明显使用。调度时应只调度：

   - `routine=true`
   - schedule 合法
   - policy enabled

7. 调度多策略

   当前 EcoBalancer 主要按 active policy 计算下一次运行。建议改为调度所有 routine 策略，或者先保守实现为：

   - active policy 自动执行
   - `routine=false` 的 active policy 不自动执行
   - 后续再升级为多策略 schedule manager

### 验收标准

- 同时执行两次 `/eb checkall`，第二次会被拒绝并显示当前进度。
- 定时任务触发时，如果手动任务正在运行，会跳过而不是重叠扣税。
- `/eb tax status` 能显示当前策略、进度、operation id。
- `routine=false` 的策略不会被自动执行，但仍可手动执行。

## 3. 建立税款账本并暴露 PlaceholderAPI

### 背景

EcoBalancer 已经能把税款转入 `tax-account`，也能记录操作影响。但从服务器运营角度看，还缺少“税款去哪了、累计收了多少、玩家交了多少、下一次什么时候收”的可见性。QuickTax 的税款统计、排行榜、PAPI 和告示牌设计值得吸收。

### 目标

- 建立独立税款账本，不只依赖 Vault 虚拟账户余额。
- 记录每个玩家累计缴税、最近缴税、本次实际扣税。
- 记录服务器累计税收、当前税款基金、最近一次征税。
- 提供 PlaceholderAPI 占位符，方便计分板、菜单、公告展示。
- 为未来排行榜、告示牌、税款再分配打基础。

### 数据库设计

建议新增表：

```sql
CREATE TABLE IF NOT EXISTS tax_ledger (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  operation_id INTEGER,
  player_uuid TEXT,
  player_name TEXT,
  policy_name TEXT,
  amount REAL NOT NULL,
  balance_before REAL,
  balance_after REAL,
  result TEXT,
  timestamp INTEGER NOT NULL
);
```

```sql
CREATE TABLE IF NOT EXISTS player_tax_totals (
  player_uuid TEXT PRIMARY KEY,
  player_name TEXT,
  latest_tax_paid REAL NOT NULL DEFAULT 0,
  total_tax_paid REAL NOT NULL DEFAULT 0,
  latest_tax_time INTEGER
);
```

```sql
CREATE TABLE IF NOT EXISTS server_tax_stats (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  total_tax_collected REAL NOT NULL DEFAULT 0,
  tax_fund_balance REAL NOT NULL DEFAULT 0,
  latest_tax_collected REAL NOT NULL DEFAULT 0,
  latest_operation_id INTEGER,
  updated_at INTEGER
);
```

### 与现有 records 的关系

`records` 继续保留作为“操作回滚与明细审计”的核心表。

`tax_ledger` 作为“税款收入账本”，只记录实际进入税款基金的金额。这样可以清晰地区分：

- 玩家余额被修正
- 玩家被免税跳过
- 实际扣税为 0
- 实际进入公共税款账户的金额

### PlaceholderAPI 设计

如果检测到 PlaceholderAPI，注册 `ecobal` expansion。

建议占位符：

```text
%ecobal_tax_fund_balance%
%ecobal_tax_total_collected%
%ecobal_tax_latest_collected%
%ecobal_tax_latest_operation%
%ecobal_player_latest_tax%
%ecobal_player_total_tax%
%ecobal_tax_next_run%
%ecobal_tax_active_policy%
%ecobal_tax_status%
%ecobal_gini%
%ecobal_top1_concentration%
```

若后续支持多策略 schedule，可增加：

```text
%ecobal_policy_next_run_<policy>%
%ecobal_policy_last_collected_<policy>%
%ecobal_policy_last_operation_<policy>%
```

### 命令设计

新增或扩展：

```text
/eb tax fund
/eb tax fund withdraw <amount> <player>
/eb tax fund add <amount>
/eb tax fund take <amount>
/eb tax top [page]
/eb tax stats [player]
```

第一阶段建议只做只读：

- `/eb tax fund`
- `/eb tax stats [player]`

提现和管理员增减属于经济高风险操作，建议等账本稳定后再实现，并复用 `require-confirmation`。

### 实现步骤

1. 扩展 `DatabaseUtils.initializeTables(...)`

   创建 `tax_ledger`、`player_tax_totals`、`server_tax_stats`。

2. 新增 `TaxLedgerService`

   负责：

   - 写入税款流水
   - 更新玩家累计税款
   - 更新服务器税款基金
   - 查询排行榜和玩家统计

3. 在 `TaxRunService` 收尾时写账本

   每个 `TaxDecision` 如果 `actualDeduction > 0`，写入 `tax_ledger`。

4. 对齐 Vault 税款账户

   如果 `tax-account=true`：

   - 扣税后继续向 Vault 税款账户入账。
   - 账本中的 `tax_fund_balance` 作为插件自己的权威统计。
   - 增加 health check，提示 Vault 税款账户余额与账本余额是否明显不一致。

5. 接入 PlaceholderAPI

   - 在 `plugin.yml` 保留或增加 `softdepend: [PlaceholderAPI]`。
   - 新增 `PapiExpansion` 类。
   - 在 `onEnable()` 中检测插件存在后注册。

6. 增加缓存

   常用统计如 `total_tax_collected`、`tax_fund_balance`、`latest_operation` 可在内存中缓存，避免每次 PAPI 请求都访问 SQLite。

7. 更新文档与语言文件

   - README 增加账本和 PAPI 说明。
   - `zh_CN.yml`、`en_US.yml` 增加命令和占位符说明。

### 验收标准

- 执行一次税收后，玩家累计缴税和服务器累计税收正确增加。
- `/eb tax fund` 能显示公共税款余额、累计收入、最近一次征税。
- `/eb tax stats <player>` 能显示该玩家最近缴税和累计缴税。
- PlaceholderAPI 存在时，占位符能在 scoreboard/menu 插件中返回有效值。
- PlaceholderAPI 不存在时，EcoBalancer 正常启动，只跳过 expansion 注册。

## 建议实施顺序

### 第一阶段：安全性

优先实现免税与欠款策略。

原因：这直接影响玩家资产安全，也是后续所有自动化任务的底线。

### 第二阶段：执行模型

实现 `TaxRunService` 和运行互斥。

原因：统一执行入口后，账本、PAPI、进度状态都能挂在同一个执行生命周期上。

### 第三阶段：可见性

实现税款账本、只读命令和 PlaceholderAPI。

原因：这会让 EcoBalancer 从“后台扣税工具”变成“可解释的经济治理系统”。

## 不建议直接照搬 QuickTax 的部分

- 不建议使用静态全局 `isCollecting` 和 `task` 保存运行状态。
- 不建议异步线程直接访问 Vault 后只靠异常兜底。
- 不建议拼接 SQL 字符串批量写入。
- 不建议把所有统计都存 YAML；EcoBalancer 已经使用 SQLite，更适合继续扩展表结构。
- 不建议把 schedule 只设计成“固定时间 + 秒级频率”，EcoBalancer 的策略系统更适合表达 daily、weekly、monthly 和未来的 cron-like schedule。

## 完成后的产品形态

完成以上三项后，EcoBalancer 会具备以下闭环：

- 管理员用策略定义“哪些死钱要被处理，以及如何处理”。
- `TaxRunService` 确保执行过程可控、不会重叠、可追踪。
- 免税与欠款策略保证玩家资产处理有明确边界。
- 账本记录税款去向，报告说明经济影响。
- PAPI 和命令让玩家、管理员、公告系统都能看到税制状态。

这会更贴合 EcoBalancer 的设计初衷：通过处理不活跃玩家沉淀资金来减少死钱，同时让服务器经济调控变得透明、可审计、可持续。
