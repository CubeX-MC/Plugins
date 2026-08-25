# StateCharge 设计

> ⚠️ **2026-08-20 计费模型已重做。** 本文中凡是描述"购买时长 / 剩余秒数 / 叠加续费 / 到期提醒"
> 的段落都已作废 —— 那是旧的**预购**模型。现模型是：玩家 toggle 开关状态，**按实际开启时长**
> 计费，关闭即停止；离线不计费；余额跌破保险阈值自动关闭全部收费状态。
>
> 当前行为以 [`README.md`](README.md) 与源码为准；下文保留是为了记录早期的取舍过程。
> 数据存档随之升到 v2（`active` / `accrued` / `guard`），v1 的预购时长无法换算，加载时明确告警并忽略。

> 状态收费插件：付费购买限时玩家状态（变小 / 变大 / 飞行 / …），Vault 经济，
> 在线时长计时、可叠加续费、配置驱动的可扩展状态框架。
> 架构照抄 Contract（共享模块参考适配插件），本文件只写 StateCharge 特有的决策。

## 1. 目标与已确认决策

| 决策点 | 结论 |
|---|---|
| 服务器平台 | Paper 1.21.x(体型走 1.20.5+ 的现代属性 API `Attribute.SCALE`,与 Clarity 同一路线,不需要 ProtocolLib;paper-api 1.21.11 已无 `Entity#setScale` 糖);编译 paper-api 1.21.11、字节码 release 17(与 Contract 相同) |
| 经济 | Vault（`depend: [Vault]`，无 provider 时 abortEnable）。**2026-08-21 起走共享模块 `cubex-economy` 的 `VaultEconomy`**，本地 `EconomyService` 已删除 |
| 钱去哪 | **2026-08-21 新增**：扣下来的钱转进 `economy.account` 指定的账户（玩家账户或 Vault bank），留空才回到旧的"销毁"行为。CubeX 服务器的经济是内循环的，收费插件不该单向减少货币总量 |
| 状态范围 | 配置驱动的可扩展框架：内置 `scale` / `fly` 两种 effect kind，内置 small/giant/fly 三个状态；服主在 `config.yml` 增删状态、改价格/时长，新增效果类型才需要代码 |
| 计时语义 | **在线时长**：离线暂停（不扣时、不生效），在线每秒扣 1；重复购买**累加**剩余时长 |

## 2. 现状调研结论（2026-08 调研）

无现成插件同时覆盖「付费 + 限时 + 多状态」：

- 限时飞行：EzFlyTime（开源，飞行券/Bossbar/PAPI，定位是飞行管理）、EconomyFlight（花钱买飞行时间）、TimedFly（中文生态）——都只有飞行；
- 变大变小：AliienResize、ScaleShift、SizeChange 都免费且不限时；LSE-Scale 是基岩版 LLSE，Pehkui 是 Fabric 模组；
- 组合方案补不上「付费限时体型」的缺口，故自研。

## 3. 架构

照抄 Contract 的分层与共享模块用法（见 `Contract/AGENTS.md`）：

| 模块 | 用途 |
|---|---|
| `cubex-core` | `CubexPlugin` 生命周期；store 经 `bind()` 走 `Terminable`；`log()`/`messager()`/`text()` |
| `cubex-config` | `ResourceFiles` 保存默认文件；`MigrationRunner` 跑 config/lang 迁移；`ReloadChain` 做 `/statecharge admin reload` |
| `cubex-i18n` | 一个 `I18nService`（MiniMessage 渲染成 legacy § 输出，与 Contract 相同，`player.sendMessage(String)` 直接可用） |
| `cubex-scheduler` | `CubexScheduler`：全局计时器只枚举在线玩家；累计、Vault 交易和实体操作均经 `runAtEntity`（Folia 安全） |
| `cubex-economy` | `VaultEconomy`：Vault 封装 + `economy.account` 入账路由；StateCharge 是它的首个消费方 |

包结构（`org.cubexmc.statecharge`）：

```
StateChargePlugin.kt       主类：enable / ReloadChain / 计时与 flush 调度
config/LanguageManager.kt  I18nService 包装（zh_CN 默认，en_US 附带）
config/StateDefinitions.kt 配置 → StateSpec 解析（Reloadable + StateCatalog）
(经济封装已下沉到 modules/cubex-economy 的 VaultEconomy，本插件不再持有副本)
effect/StateEffect.kt      effect 接口：start / reapply / remove
effect/ScaleEffect.kt      setScale 体型
effect/FlightEffect.kt     allowFlight + 可选自动起飞
model/StateSpec.kt         状态定义
service/StateChargeService.kt  购买/赠送/清除/计时核心（纯逻辑，可测）
service/StateNotifier.kt   计时提醒接口 + I18n 实现
storage/StateStorage.kt    states.yml 持久化（Reloadable + Terminable）
command/StateChargeCommand.kt  /statecharge + tab 补全
listener/StateListener.kt  join/respawn/换世界/换模式 → 重放效果
util/TimeFormat.kt         秒数 → 人类可读时长（语言化单位）
```

## 4. 领域模型

### 4.1 StateSpec（`config.yml` 的 `states.<id>`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | bool | true | false 时不可购买（admin give 仍可用） |
| `display` | string | = id | 展示名（可含 MiniMessage） |
| `price` | decimal | 0 | 每份价格（0 = 免费） |
| `unit-seconds` | long | 1800 | 每份时长（秒） |
| `max-stack-seconds` | long | 0 | 累计上限，0 = 不限 |
| `permission` | string | "" | 空 = 不检查；否则购买前检查该权限节点 |
| `conflict-group` | string | 按 effect 派生 | 同组状态互斥（scale→"scale"，fly→"fly"）；显式 `""` 关闭互斥 |
| `effect` | section | 必填 | `type: scale`（参数 `scale`）或 `type: fly`（参数 `auto-start`） |

解析规则：id 必须匹配 `[a-z0-9_-]{1,32}`；非法/未知 effect type 的状态条目在加载时记 severe 日志并**跳过该条目**（不炸服），`/statecharge admin reload` 后可见。

### 4.2 StateEffect

```kotlin
interface StateEffect {
    fun start(player: Player)     // 购买瞬间：施加 + 可能的"起跳"（如自动起飞）
    fun reapply(player: Player)   // 幂等重放：join/respawn/换世界/换模式
    fun remove(player: Player)    // 到期/清除时清理
}
```

- `ScaleEffect(id, scale)`：经 `PlayerStateLeaseStack` 写入 `scale` 层；remove 只弹出自己的层，
  恢复下一层或首次施加前的基线。
  值域校验 0.1..16.0(属性本身的 clamp 范围 0.0625..16)。
- `FlightEffect(id, autoStart)`：`allowFlight` 同样走共享 lease 栈；autoStart 只在首次开启时起飞，
  reapply 不强迫玩家保持飞行。移除后仍遵守 creative/spectator 与 `statecharge.fly.keep`。

### 4.3 计时模型

- 全局 `runGlobalTimer`，周期 = `timing.tick-seconds`（默认 1，最小 1）。
- tick 只做**存储与消息**（全局任务上下文安全）：逐个在线玩家扣减剩余秒数；
  到期时经 `runAtEntity` 移除效果。效果施加/重放全部走事件 + `runAtEntity`。
- 离线玩家不扣时；`states.yml` 存剩余秒数，崩溃最多丢 `storage.flush-interval-seconds` 内的扣减（对玩家有利，可接受）。
- 提醒：剩余秒数命中 `notifications.expiry-warning-seconds` 发聊天提示；
  剩余 ≤ `notifications.actionbar-countdown-seconds` 时每 tick 刷 actionbar 倒计时（0 关闭）。

## 5. 命令与权限

```
/statecharge [help]                    → 帮助
/statecharge list                      → 可购买状态（价格/每份时长/上限）
/statecharge status                    → 我的生效状态与剩余时间
/statecharge buy <状态> [份数]          → 购买（默认 1 份，单次 ≤ 1000 份）
/statecharge admin give <玩家> <状态> <秒数>   → 免费发放（叠加,不受 enabled/max-stack 限制）
/statecharge admin clear <玩家> [状态]   → 缺省清空全部
/statecharge admin reload              → ReloadChain 重载（返回 ReloadReport 定位失败段）
别名: /sc
```

| 权限 | 默认 | 说明 |
|---|---|---|
| `statecharge.use` | true | list/status/buy/help |
| `statecharge.buy.<id>` | true | 仅当该状态的配置 `permission` 指向它时才检查 |
| `statecharge.fly.keep` | false | 飞行到期后保留飞行 |
| `statecharge.admin.{give,clear,reload}` | op | 子权限，`statecharge.admin` 汇总 |

购买失败 → 消息映射：UNKNOWN_STATE / DISABLED / NO_PERMISSION / CONFLICT（同组互斥，附对方状态名）/
MAX_STACK / INSUFFICIENT_FUNDS / ECONOMY_FAILED（附 Vault 原因）/ INVALID_COUNT。

## 6. 存储

`states.yml`（Reloadable + Terminable，`bind(store)` 管理关停 flush）：

```yaml
players:
  <uuid>:
    <state-id>: <剩余秒数>
```

- 写盘：tmp 文件 + 原子 move；save 前把旧文件拷到 `states.yml.bak`；load 失败自动回退 .bak。
- dirty 标记：tick 每秒 markDirty，flush 周期 = `storage.flush-interval-seconds`（默认 60）。

## 7. i18n

- `lang/zh_CN.yml`、`lang/en_US.yml`，全部 MiniMessage，`<prefix>` token，key 分 `messages.*`（聊天）
  与 `ui.*`（命令行渲染）。语言文件带 `lang-version: 1`，新增 key 走迁移步骤（LangV2ToV3Step 模式）。
- `LanguageParityTest` 守卫：两份 locale key 集一致、占位符一致、源码里 `ui("…")` / `message("…")` 引用的 key 都存在、Kotlin 里无 legacy 颜色字面量。
- 时长渲染走 `TimeFormat` + `ui.time-{day,hour,minute,second}` 单位标签（如「1小时30分」/「1h 30m」）。

## 8. 测试

| 测试 | 覆盖 |
|---|---|
| `StateDefinitionsTest` | 配置解析：默认值、非法条目跳过、互斥组派生、effect 参数校验 |
| `StateChargeServiceTest` | 购买成功/叠加/上限/互斥/余额不足/经济失败、tick 扣减与到期清理、give/clear（Mockito mock Player/EconomyService/CubexScheduler，真 StateStorage + 临时文件） |
| `StateStorageTest` | roundtrip、dirty flush、损坏回退 .bak |
| `TimeFormatTest` | 时长渲染与零组件省略 |
| `LanguageParityTest` | 见 §7 |

## 9. 范围外（v1 不做，后续可加）

GUI 商店、BossBar 倒计时、PlaceholderAPI、MySQL、现实时间倒计时模式、
bStats Metrics（需注册服务 ID 后再加）、跨服（BungeeCord）同步。
