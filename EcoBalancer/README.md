# EcoBalancer: 智能经济平衡插件

[English](README_en.md) | 简体中文

EcoBalancer 是一个智能的 Minecraft 经济插件，通过对不活跃玩家实施智能税收系统来优化服务器经济。它促进公平竞争，创造活跃的游戏环境，并为服务器经济提供智能管理解决方案。

## 主要特点

- 自动化且可配置的税收设置
- 税收收入存入公共账户
- 财富分配直方图
- 玩家余额的基本统计数据（如平均值和标准差）

![Imgur](https://i.imgur.com/0eXcPeO.gif)

![Imgur](https://imgur.com/L7wagZ9.gif)

## 功能特性

- 自动定期（每日/每周/每月）对不活跃玩家账户征税
- 根据余额等级和活跃度自定义扣除率
- 手动征税命令，可按需调整

**注意**：\
EcoBalancer 目前正在测试中。我们建议在部署前进行严格评估。如有bug反馈或建议，请提交 issue。
另外，更新时请确保删除旧的配置文件和语言文件。

**前置插件**：Vault

## 命令

所有命令需要使用 `/ecobal` 前缀（或别名 `/eb`）：

### 基础命令
- `/ecobal help`：显示帮助信息
- `/ecobal reload`：重新加载配置文件
- `/ecobal checkall [filters...]`：根据配置与过滤参数更新玩家余额
- `/ecobal checkplayer <player>`：根据配置设置更新指定离线玩家的余额
- `/ecobal gui`：打开 GUI 仪表盘/策略菜单
- `/ecobal migrate <check|run|backup>`：检查/执行迁移并创建备份
- `/ecobal tax ...`：管理税收配置与策略执行
- `/ecobal tax status`：查看当前征税任务进度
- `/ecobal tax fund`：查看税款账本余额、累计征收和最近一次征税
- `/ecobal tax stats [player]`：查看玩家最近缴税与累计缴税
- `/ecobal policy <list|set|execute>`：转发到 tax 策略管理

### 经济分析命令
- `/ecobal gini [filters...]`：计算基尼系数（衡量贫富差距）。支持可选过滤参数（见下文“过滤参数”）。
- `/ecobal concentration [percentages...] [filters...]`：财富集中度分析，默认显示 Top 1%, 5%, 10%, 20%，也可自定义百分比并附加过滤参数。
- `/ecobal stats <bars> [filters...]`：显示描述性统计和财富分布直方图，bars 为柱数，其余为过滤参数。
- `/ecobal interval [filters...] [alphabet|balance] [page]`：按过滤参数筛选玩家余额并分页显示，可选按名称或余额排序。
- `/ecobal perc <balance> [filters...]`：在给定过滤条件集合下，显示指定余额所处的百分位数。
- `/ecobal report [operation_id]`：查看税收操作报告，显示征税总额、影响人数、税阶分布等
- `/ecobal health [filters...]`：通过多项指标检查服务器经济健康状况，支持过滤参数。
- `/ecobal impact [operation_id]`：查看某次操作的税收影响分析
- `/ecobal trends [days]`：查看经济趋势（基于快照历史）

### 记录管理命令
- `/ecobal checkrecords [page]`：显示所有操作记录
- `/ecobal checkrecord <operation_id> [sort] [page]`：显示特定操作的详细信息
- `/ecobal restore <operation_id>`：恢复特定操作

**别名**：可以使用 `/eb` 代替 `/ecobal`，例如 `/eb gini` 等同于 `/ecobal gini`

## 权限节点

核心权限：

- `ecobalancer.command.ecobal`：主命令入口
- `ecobalancer.command.*`：细粒度子命令权限（`checkall`、`checkplayer`、`stats`、`interval`、`perc`、`checkrecords`、`checkrecord`、`restore`、`gini`、`concentration`、`report`、`health`、`impact`、`trends`、`tax`、`migrate`、`reload`）
- `ecobalancer.gui.view`：打开 GUI 仪表盘
- `ecobalancer.gui.admin`：GUI 策略管理与立即执行
- `ecobalancer.admin`：管理员通知与迁移相关操作
- `ecobalancer.exempt`：免税权限（由配置控制）
- `ecobalancer.exempt.policy.<policy>`：对指定策略免税
- `ecobalancer.exempt.operation.<operation>`：对指定执行类型免税，例如 `checkall`、`checkplayer`、`policy`

### 过滤参数（适用于 gini / concentration / health / stats / interval / perc / checkall 等分析类命令）

采用 WorldEdit 风格的 `key:value` 参数，可以与位置参数混用、顺序不限；同一维度多条件取交集（更严格者生效）。示例：

- `d:N`：仅统计最近 N 天内活跃的玩家（基于 `lastPlayed`）。
- `p:N`：仅统计累计在线时长 ≥ N 小时的玩家（从 vanilla `stats/*.json` 读取，插件启动后异步缓存）。
- `l:X`：仅统计余额 ≥ X 的玩家（下界）。
- `u:X`：仅统计余额 ≤ X 的玩家（上界）。
- `lr:P`：仅统计余额 ≥ P 分位阈值（相对下界，P∈[0,100]）。
- `ur:P`：仅统计余额 ≤ P 分位阈值（相对上界，P∈[0,100]）。

组合规则：
- 活跃度维度（`d` 与 `p`）与财富维度（`l/u/lr/ur`）做 AND 组合。
- 财富上下界相互取交集：`min = max(l, percentile(lr))`，`max = min(u, percentile(ur))`；若 `min > max`，结果为空。

示例：
- `/eb gini d:30 lr:80` 仅统计近 30 天活跃且属于 Top 20% 的玩家；
- `/eb concentration 1 10 p:50 l:100000 u:10000000` 在线 50 小时以上且余额在 10万-1000万；
- `/eb stats 20 ur:60` 仅统计不超过 60% 分位的玩家，绘制 20 段直方图。
- `/eb interval d:30 l:10000 alphabet 2` 近 30 天活跃且余额 ≥1万，按名称排序，第 2 页。
- `/eb perc 50000 p:20 lr:10 ur:90` 在线≥20 小时、位于 10%-90% 分位集合内时，余额 5 万处于的百分位。

注意：`p` 依赖 vanilla 统计文件，首次加载可能需要数秒；插件会在后台缓存并增量刷新，尽可能降低影响。

### 税收目标过滤（checkall）

- `config.yml` 新增：
  - `tax-filters: "d:30 p:10 lr:80 l:100000"` 作为 checkall 的默认征税过滤器（可留空表示不过滤）。
  - `record-zero-deduction: false` 当为 false 时，跳过扣款为 0 的记录写入。
- 命令：`/ecobal checkall [filters...]` 支持传入与分析命令相同的过滤参数；传参时将覆盖配置中的 `tax-filters`。

## 配置 (config.yml)

```yaml
language: 'en_US' # 语言 en_US/zh_CN
info-on-login: true # 登录时显示用户信息
record-retention-days: 30 # 记录保留天数
check-time: "20:00" # 时间格式为 HH:mm
check-schedule:
 type: 'weekly' # 选项：'daily'（每日）, 'weekly'（每周）, 'monthly'（每月）
 days-of-week: [2, 4, 6] # 周一、周三、周五（7 = 周六，1 = 周日）
 dates-of-month: [1] # 每月1号
deduct-based-on-time: true
# 以下两个选项仅在 deduct-based-on-time 为 true 时生效
inactive-days-to-deduct: 50 # 开始扣除前的不活跃天数
inactive-days-to-clear: 500 # 清除余额前的不活跃天数
# 扣除的税收等级
tax-brackets:
 - threshold: 100000
   rate: 0.001 # 税率
 - threshold: 1000000
   rate: 0.01 # 税率
 - threshold: null # 无限制
   rate: 0.02 # 税率
tax-account: true # 是否使用税收账户
tax-account-name: 'tax' # 税收账户名称
tax-exempt:
  enabled: true
  global-permission: 'ecobalancer.exempt'
  policy-permission-prefix: 'ecobalancer.exempt.policy'
  operation-permission-prefix: 'ecobalancer.exempt.operation'
debt-mode: 'skip' # skip=余额不足跳过, drain=最多扣到0, allow-negative=允许负数
debt-commands:
  - 'broadcast &e%player% &cdoes not have enough money to pay taxes.'
only-offline-players: true
stats-world: ''
tax-filters: ''
record-zero-deduction: false
file-logging: true # 是否写入插件日志文件 plugins/EcoBalancer/logs/latest.log
require-confirmation: true # 高风险操作是否需要确认
tax-exempt-permission: 'ecobalancer.exempt'
max-deduction-per-player: 0 # 单次最大扣款（0 = 不限制）
min-balance-protection: 0 # 最低余额保护（0 = 关闭）
```

## 税款账本与 PlaceholderAPI

EcoBalancer 会把实际扣除的税款写入独立账本，用于 `/eb tax fund`、`/eb tax stats [player]` 和税收报告。若安装 PlaceholderAPI，插件会注册 `ecobal` expansion：

- `%ecobal_tax_fund_balance%`
- `%ecobal_tax_total_collected%`
- `%ecobal_tax_latest_collected%`
- `%ecobal_tax_latest_operation%`
- `%ecobal_player_latest_tax%`
- `%ecobal_player_total_tax%`
- `%ecobal_tax_next_run%`
- `%ecobal_tax_active_policy%`
- `%ecobal_tax_status%`
- `%ecobal_gini%`
- `%ecobal_top1_concentration%`

## 迁移命令

- `/eb migrate check`：检查 config/lang 版本状态
- `/eb migrate run`：执行迁移并重载配置
- `/eb migrate backup`：手动创建配置备份

## 常见问题排查

- Vault 缺失导致插件禁用：确认已安装 Vault 与一个经济实现插件。
- 数据量大时命令卡顿：建议使用过滤参数，避开高峰时段执行。
- 语言键缺失或文案异常：执行 `/eb migrate run` 合并最新语言键。
- `trends` 无数据：等待快照定时任务生成至少一条历史记录。

## 依赖升级策略

- 在 `pom.xml` 中通过 properties 固定关键依赖版本。
- 定期检查依赖更新（尤其是 `sqlite-jdbc`），升级后执行完整命令回归。
- 建议分批升级（先构建插件版本，再运行时依赖），便于定位回归和回滚。

[![Forkers repo roster for @CubeX-MC/EcoBalancer](https://reporoster.com/forks/CubeX-MC/EcoBalancer)](https://github.com/CubeX-MC/EcoBalancer/network/members)
[![Stargazers repo roster for @CubeX-MC/EcoBalancer](https://reporoster.com/stars/CubeX-MC/EcoBalancer)](https://github.com/CubeX-MC/EcoBalancer/stargazers) 
