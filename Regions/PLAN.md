# Regions 插件设计方案与实现计划

## 1. 目标定位

Regions 是 CubeX 插件体系下的“特殊场地编排插件”。它不重新实现领地、选区和所有权，而是把 Lands、未来的 Residence、WorldGuard 等外部区域统一成可配置的 Region，再允许 RuleGems 的统治者为这些区域安装玩法、规则、效果和 IF/THEN 行为。

一句话定位：

> 外部领地插件负责“哪里是区域、谁拥有区域”；Regions 负责“这个区域在服务器规则中变成什么场地”。

首要使用者是 RuleGems 的统治者和服务器管理员，所以设计必须同时满足：

- 好理解：GUI 和命令以“区域、玩法、规则、效果、触发器”组织，不暴露太多底层细节。
- 足够灵活：简单场地靠 flag/effect/action 拼装，复杂玩法写成 mode。
- 安全可恢复：玩家在区域内获得的异常属性只能存在于区域内，离开、死亡、掉线、重载、插件关闭时必须被清理。
- 可扩展：区域来源、工会来源、玩法模式、效果、动作都通过注册表扩展。

## 2. 与当前 CubeX Kotlin 架构的关系

Regions 应作为根目录独立子项目，结构和 `Contract`、`RuleGems` 保持一致：

```text
Regions/
  build.gradle.kts
  PLAN.md
  src/main/java/org/cubexmc/regions/
    RegionsPlugin.kt
    api/
    command/
    config/
    effect/
    action/
    flag/
    gui/
    integration/
    listener/
    mode/
    model/
    service/
    storage/
    util/
  src/main/resources/
    plugin.yml
    config.yml
    regions.yml
    templates.yml
    lang/zh_CN.yml
    lang/en_US.yml
  src/test/java/org/cubexmc/regions/
```

实现风格建议：

- 主类继承 `org.cubexmc.core.CubexPlugin`，使用 `enablePlugin()` / `disablePlugin()`。
- 使用 `ResourceFiles` 保存默认资源。
- 使用 `MigrationRunner` 管理 `config.yml`、`regions.yml`、`templates.yml` 和语言文件版本。
- 使用 `bind { ... }` 和 `bindTask(...)` 托管关闭逻辑，确保所有 region session、临时效果、GUI session 和任务都被清理。
- 命令、GUI、listener、service、storage 分层，避免把业务逻辑塞进监听器或命令类。
- Folia/Paper 调度尽量复用现有 `modules:cubex-scheduler` 或采用和 RuleGems `SchedulerUtil` 同等的安全封装，后续可统一迁入共享 scheduler。

`build.gradle.kts` 应类似 `Contract`：

```kotlin
plugins { id("cubex-kotlin-plugin") }

version = "0.1.0"
description = "Regions"

dependencies {
    compileOnly(CubexDeps.paperApi("1.21.11-R0.1-SNAPSHOT"))
    compileOnly(CubexDeps.vault)
    compileOnly("com.github.angeschossen:LandsAPI:<version>")
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(CubexDeps.mockitoCore)
}
```

`settings.gradle.kts` 后续加入：

```kotlin
listOf(..., "Regions").forEach {
    include(":$it"); project(":$it").projectDir = file(it)
}
```

## 3. 核心概念

### 3.1 Region

Region 是 Regions 插件管理的场地实例。它引用外部区域来源，而不是自己存储完整选区。

```kotlin
data class RegionDefinition(
    val id: String,
    val name: String,
    val source: RegionSourceRef,
    val ownerPolicy: OwnerPolicy,
    val enabled: Boolean,
    val mode: ModeConfig?,
    val flags: Map<String, FlagConfig>,
    val effects: List<EffectConfig>,
    val triggers: Map<RegionTrigger, List<ActionConfig>>,
    val metadata: Map<String, String>,
)
```

`id` 使用稳定内部 id，例如 `mini_kingdom`。外部区域引用单独存储：

```yaml
regions:
  mini_kingdom:
    name: "小人国"
    source:
      type: lands
      world: world
      land: capital
      area: miniature
```

这样未来 Lands 区域重命名、迁移或 Residence 接入时，有空间做兼容迁移。

### 3.2 Source

Source 负责连接外部区域系统。

```kotlin
interface RegionSource {
    val type: String
    fun isAvailable(): Boolean
    fun resolve(ref: RegionSourceRef): ExternalRegion?
    fun contains(ref: RegionSourceRef, location: Location): Boolean
    fun getOwnedRegions(playerId: UUID): List<ExternalRegion>
}
```

首个实现：

```kotlin
class LandsRegionSource : RegionSource
```

后续实现：

```kotlin
class ResidenceRegionSource : RegionSource
class WorldGuardRegionSource : RegionSource
class CuboidRegionSource : RegionSource
```

`CuboidRegionSource` 可作为无 Lands 环境的测试/开发 fallback，但不应成为 MVP 的主方向。

### 3.3 UnionProvider

玩家组织统一称为工会，抽象命名用 `UnionProvider`，不要把 Lands nation 泄漏到玩法层。

```kotlin
interface UnionProvider {
    val type: String
    fun isAvailable(): Boolean
    fun getUnion(playerId: UUID): UnionRef?
    fun areSameUnion(a: UUID, b: UUID): Boolean
    fun areAllied(a: UUID, b: UUID): Boolean
    fun areEnemies(a: UUID, b: UUID): Boolean
    fun placeholder(playerId: UUID, key: String): String?
}
```

首个实现：

```kotlin
class LandsUnionProvider : UnionProvider
```

未来实现：

```kotlin
class PlayerGuildUnionProvider : UnionProvider
class GuildsUnionProvider : UnionProvider
class FallbackUnionProvider : UnionProvider
```

Mode 只依赖 `UnionProvider`，不直接调用 Lands API。

### 3.4 Mode

Mode 是成品玩法。凡是有完整流程、状态机、队伍、计分、排行榜、倒计时、结算的内容，都应该写成 Mode。

适合 Mode 的例子：

- `free_event`：自由活动场地，主要承载 flags/effects/actions。
- `dual_pvp`：双人 PVP 约战。
- `union_war`：工会战。
- `parkour`：跑酷。
- `race`：赛道，可支持马、船、矿车、冰船等。
- `hide_and_seek`：藏猫猫。
- `capture_point`：占点。

接口草案：

```kotlin
interface RegionMode {
    val type: String
    fun createRuntime(region: RegionDefinition, config: ModeConfig): RegionModeRuntime
    fun validate(region: RegionDefinition, config: ModeConfig): List<ValidationIssue>
}

interface RegionModeRuntime {
    val regionId: String
    fun enable(ctx: RegionRuntimeContext) {}
    fun disable(ctx: RegionRuntimeContext) {}
    fun onPlayerEnter(ctx: RegionPlayerContext) {}
    fun onPlayerLeave(ctx: RegionPlayerContext) {}
    fun onPlayerDeath(ctx: RegionPlayerContext) {}
    fun onCommand(ctx: RegionCommandContext): CommandResult = CommandResult.pass()
    fun tick(ctx: RegionRuntimeContext) {}
}
```

`RegionMode` 是工厂和校验器；`RegionModeRuntime` 是实际运行状态。这样 `dual_pvp`、`parkour` 这类有运行中状态的玩法不会污染静态配置对象。

### 3.5 Flag

Flag 是区域规则开关，负责拦截某类服务器行为。它不应该承载复杂游戏流程。

例子：

- `pvp`: allow / deny / pass
- `fly`: allow / deny / pass
- `vanish`: allow / deny / pass
- `item_drop`: allow / deny / pass
- `item_pickup`: allow / deny / pass
- `block_break`: allow / deny / pass
- `block_place`: allow / deny / pass
- `vehicle_enter`: allow / deny / pass
- `commands`: allowlist / blocklist
- `teleport_out`: allow / deny / require_confirm

Flag 使用三态结果：

```kotlin
enum class RuleResult {
    ALLOW,
    DENY,
    PASS,
}
```

`PASS` 表示 Regions 不干预，交给 Lands、原版、其他插件或默认逻辑。这样可以避免和 Lands/WorldGuard 的规则体系硬冲突。

接口草案：

```kotlin
interface RegionFlag {
    val key: String
    fun evaluate(ctx: FlagContext): RuleResult
    fun validate(config: FlagConfig): List<ValidationIssue>
}
```

### 3.6 Effect

Effect 是由 Regions 托管的区域内临时效果。`scale` 必须做成 Effect，而不是 action 或 command。

Effect 的关键是 scope：

```kotlin
enum class EffectScope {
    WHILE_INSIDE,
    UNTIL_MODE_END,
    TIMED,
}
```

核心接口：

```kotlin
interface ScopedEffect {
    val type: String
    fun apply(ctx: EffectApplyContext): EffectLease
    fun refresh(ctx: EffectRefreshContext, lease: EffectLease) {}
    fun restore(ctx: EffectRestoreContext, lease: EffectLease)
    fun validate(config: EffectConfig): List<ValidationIssue>
}
```

`EffectLease` 必须记录：

```kotlin
data class EffectLease(
    val id: UUID,
    val playerId: UUID,
    val regionId: String,
    val effectType: String,
    val scope: EffectScope,
    val appliedAtMillis: Long,
    val expiresAtMillis: Long?,
    val snapshot: PlayerStateSnapshot,
    val metadata: Map<String, String>,
)
```

首批 Effects：

- `scale`: 缩放体型，支持小人国、巨人场地等。
- `potion`: 药水效果，采用 RuleGems `EffectConfig` 的“限时 + 周期重施”思路，避免孤儿永久效果。
- `walk_speed`: 行走速度。
- `fly_speed`: 飞行速度。
- `allow_flight`: 允许飞行。
- `glowing`: 发光。
- `collision`: 碰撞开关。
- `health_scale`: 血量显示比例。
- `temporary_inventory`: 临时物品包。
- `scoreboard`: 区域计分板。
- `bossbar`: 区域 bossbar。
- `permission_attachment`: 临时权限。

`scale` 配置示例：

```yaml
effects:
  - type: scale
    value: 0.35
    scope: while_inside
    transition-ticks: 10
```

实现要求：

- 优先使用 Paper/Spigot 可用的原生 Attribute 或 API。
- 如果当前服务端版本不支持 `scale`，配置校验必须明确报错，GUI 显示“当前服务端不支持 scale effect”。
- 不允许用普通 console command 替代内建 scale effect，因为 command 不可可靠恢复原始值。
- 进入区域时保存原始 scale，离开时恢复。
- 插件 disable/reload 时恢复所有在线玩家 scale。

### 3.7 Action

Action 主要用于 IF/THEN 行为，方便统治者在服务器内配置。它是“事件发生时做某件事”，不是持续状态。

设计目标：

- GUI 可以选择事件、条件、动作。
- YAML 可以表达复杂但可读的 IF/THEN。
- 动作可以调用 EffectService，但不能绕过安全边界直接改玩家状态。
- 命令类 action 默认安全执行，避免 RuleGems 曾经的 OP 提权风险扩散。

Action 接口：

```kotlin
interface RegionAction {
    val type: String
    fun execute(ctx: ActionContext): ActionResult
    fun validate(config: ActionConfig): List<ValidationIssue>
}
```

Action 配置：

```kotlin
data class ActionConfig(
    val type: String,
    val conditions: List<ConditionConfig>,
    val arguments: Map<String, Any>,
)
```

首批 Actions：

- `message`
- `title`
- `sound`
- `console_command`
- `player_command`
- `effect_apply`
- `effect_clear`
- `teleport`
- `give_item`
- `take_item`
- `set_metadata`
- `clear_metadata`
- `mode_command`
- `economy`
- `permission_group`

Action 不直接包括 `scale`，但可以通过 `effect_apply` 调用 scale：

```yaml
triggers:
  on_enter:
    - if:
        - permission: "regions.mini.enter"
      then:
        - type: effect_apply
          effect:
            type: scale
            value: 0.35
            scope: while_inside
        - type: message
          text: "&a你进入了小人国"
```

### 3.8 Trigger 与 IF/THEN

Trigger 是事件入口，Action 是执行结果，Condition 是条件。

首批 Trigger：

- `on_enter`
- `on_leave`
- `on_death`
- `on_kill`
- `on_respawn`
- `on_interact`
- `on_command`
- `on_timer`
- `on_mode_start`
- `on_mode_end`
- `on_score`
- `on_checkpoint`
- `on_finish`

Condition 例子：

- 玩家权限
- 是否统治者
- 是否区域拥有者
- 是否同工会
- 是否敌对工会
- 背包是否有物品
- 经济余额
- 时间窗口
- 在线人数
- Mode 状态
- Region metadata
- Placeholder 匹配

YAML 形态：

```yaml
triggers:
  on_enter:
    - name: "小人国入场"
      if:
        - permission: "regions.template.mini"
        - not:
            flag: "player.in_combat"
      then:
        - type: effect_apply
          effect:
            type: scale
            value: 0.35
            scope: while_inside
        - type: title
          title: "&a小人国"
          subtitle: "&7所有特殊属性离开后会自动恢复"
      else:
        - type: message
          text: "&c你现在不能进入这个活动区域"
```

GUI 中不应该显示“脚本编辑器”，而是显示：

```text
事件: 玩家进入区域
条件: 拥有权限 regions.template.mini
满足时:
  - 应用效果: scale = 0.35, while_inside
  - 发送标题: 小人国
不满足时:
  - 发送消息: 你现在不能进入
```

## 4. 安全模型

安全模型是 Regions 的核心功能，不是附属功能。

原则：

1. 所有区域临时状态必须通过 `ScopedEffectService` 申请 lease。
2. Mode、Action、Flag 不得直接留下不可追踪的玩家属性修改。
3. 玩家离开区域后必须恢复到进入前或插件接管前的状态。
4. 若无法恢复，必须记录日志并提供 `/regions cleanup` 人工修复。
5. 尽量使用“限时 + 周期重施”，不要依赖永久效果。

### 4.1 Session

玩家进入区域时创建 `RegionSession`：

```kotlin
data class RegionSession(
    val id: UUID,
    val playerId: UUID,
    val regionId: String,
    val enteredAtMillis: Long,
    val sourceLocation: LocationSnapshot,
    val activeLeases: MutableList<EffectLease>,
    val metadata: MutableMap<String, String>,
)
```

Session 由 `RegionSessionService` 管理：

```kotlin
class RegionSessionService {
    fun enter(player: Player, region: RegionDefinition)
    fun leave(player: Player, regionId: String, reason: LeaveReason)
    fun cleanup(player: Player, reason: CleanupReason)
    fun activeSessions(playerId: UUID): List<RegionSession>
}
```

### 4.2 Snapshot

需要恢复的状态包括：

- `scale`
- `allowFlight`
- `isFlying`
- `walkSpeed`
- `flySpeed`
- `gameMode`
- `potionEffects`
- `healthScale`
- `collidable`
- `glowing`
- `scoreboard`
- `bossbar`
- `inventory` 临时替换
- `metadata`
- `permissions attachment`
- mode 临时队伍/计分板/旁观者状态

Snapshot 要分层：

- `PlayerStateSnapshot`：通用玩家状态。
- `EffectLease.snapshot`：单个 effect 需要恢复的局部状态。
- `ModeSessionSnapshot`：玩法自己的状态。

不要每个 effect 都全量保存整个玩家状态，否则多个 effect 叠加时很容易互相覆盖。推荐由每个 effect 保存自己负责的字段。

### 4.3 清理入口

必须触发 cleanup 的入口：

- `PlayerMoveEvent` 从区域内移动到区域外。
- `PlayerTeleportEvent` 从区域内传送到区域外。
- `PlayerQuitEvent`
- `PlayerKickEvent`
- `PlayerDeathEvent`
- `PlayerRespawnEvent`
- `WorldUnloadEvent`
- `PluginDisableEvent`
- `/regions reload`
- 区域被禁用。
- Mode 被切换或卸载。
- Source 不可用，例如 Lands 被卸载或区域不存在。

同时实现 watchdog：

```text
每 2-5 秒扫描在线玩家 session:
  如果玩家不在线，清理内存 session。
  如果玩家已经不在 region 内，执行 leave cleanup。
  如果 effect lease 过期，恢复或刷新。
  如果 region/source 不可用，执行 failsafe cleanup。
```

Watchdog 不是主流程，但能兜住事件漏发、插件顺序变化、兼容问题。

### 4.4 命令安全

Action 中的命令执行要借鉴 RuleGems `CustomCommandExecutor` 的安全经验，但默认更保守：

- `console_command`：由控制台执行，支持白名单变量替换。
- `player_command`：玩家本人执行，不提权。
- 不提供默认 `player-op`。
- 如果未来提供提权执行，必须全局配置显式开启，并在启动时输出高危警告。
- 命令变量采用 `{player}`、`{uuid}`、`{region}`、`{union}`，替换后必须移除前导 `/`。
- GUI 中对高危 action 显示警告。

## 5. 配置设计

### 5.1 config.yml

```yaml
config-version: 1

language: zh_CN

integrations:
  lands:
    enabled: true
    required-for-startup: false
  rulegems:
    enabled: true
  placeholderapi:
    enabled: true

safety:
  watchdog-interval-seconds: 3
  cleanup-on-death: true
  cleanup-on-quit: true
  cleanup-on-reload: true
  deny-unsafe-player-op-actions: true
  max-actions-per-trigger: 16
  max-regions-per-player: 5

effects:
  refresh-interval-ticks: 60
  default-duration-ticks: 400
  scale:
    min: 0.1
    max: 4.0
    require-native-support: true

commands:
  root: regions
  aliases:
    - region
    - venue
```

### 5.2 regions.yml

```yaml
regions-version: 1

regions:
  mini_kingdom:
    name: "小人国"
    enabled: true
    source:
      type: lands
      land: capital
      area: miniature
    owner-policy: lands_owner
    mode:
      type: free_event
    flags:
      pvp: deny
      fly: deny
      vanish: deny
      commands:
        mode: blocklist
        values:
          - home
          - spawn
    effects:
      - type: scale
        value: 0.35
        scope: while_inside
        transition-ticks: 10
      - type: potion
        effect: speed
        amplifier: 1
        scope: while_inside
    triggers:
      on_enter:
        - name: "入场提示"
          then:
            - type: title
              title: "&a小人国"
              subtitle: "&7离开后体型会自动恢复"
      on_leave:
        - name: "离场提示"
          then:
            - type: message
              text: "&7你离开了小人国"
```

### 5.3 templates.yml

模板用于降低统治者配置成本。

```yaml
templates-version: 1

templates:
  mini_kingdom:
    name: "小人国"
    description: "玩家进入后缩小，禁飞、禁隐身、禁 PVP。"
    mode:
      type: free_event
    flags:
      pvp: deny
      fly: deny
      vanish: deny
    effects:
      - type: scale
        value: 0.35
        scope: while_inside
    triggers:
      on_enter:
        - then:
            - type: title
              title: "&a小人国"
              subtitle: "&7离开区域后自动恢复"

  boat_race:
    name: "划船赛"
    mode:
      type: race
      vehicle: boat
      laps: 3
    flags:
      pvp: deny
      fly: deny
      item_drop: deny
```

## 6. 管理体验

统治者不应该被迫理解 YAML。命令和 GUI 要围绕“创建场地”设计。

### 6.1 命令

基础命令：

```text
/regions
/regions list
/regions create <id> <name>
/regions bind <id> lands <land> <area>
/regions remove <id>
/regions enable <id>
/regions disable <id>
/regions reload
```

配置命令：

```text
/regions mode set <id> <mode>
/regions flag set <id> <flag> <value>
/regions effect add <id> scale <value>
/regions effect remove <id> <effect-id>
/regions trigger add <id> <trigger>
/regions template apply <id> <template>
```

调试和安全：

```text
/regions inspect <player>
/regions cleanup <player>
/regions validate <id>
/regions doctor
/regions test enter <id>
/regions test leave <id>
```

玩法命令：

```text
/regions game <id> start
/regions game <id> stop
/regions game <id> join
/regions game <id> leave
/regions game <id> role <player> <role>
```

### 6.2 GUI

GUI 层建议复用 `Contract` 的 `MenuRegistry` / `Menu` / `InventoryButton` 风格，建立 Regions 自己的轻量 GUI framework，或后续抽成共享模块。

页面结构：

```text
RegionsMainMenu
  RegionListMenu
  RegionDetailMenu
    SourceBindMenu
    ModeMenu
    FlagMenu
    EffectMenu
    TriggerMenu
    PermissionMenu
    ValidateMenu
    DebugMenu
  TemplateMenu
```

统治者视角的核心流程：

1. `/regions` 打开 GUI。
2. 点击“创建区域”。
3. 选择“绑定 Lands 领地”。
4. 选择自己拥有或被授权管理的 Lands area。
5. 选择模板，例如“小人国”。
6. GUI 显示校验结果。
7. 点击启用。

GUI 文案必须把概念翻译成管理语言：

- Mode = 玩法
- Flag = 规则
- Effect = 区域内状态
- Trigger = 当...时...
- Action = 执行...
- Condition = 仅当...

## 7. 服务分层

建议服务类：

```text
RegionsPlugin
  RegionRegistry
  RegionStorage
  RegionSourceRegistry
  UnionProviderRegistry
  RegionModeRegistry
  RegionFlagRegistry
  ScopedEffectService
  RegionActionRegistry
  RegionTriggerService
  RegionSessionService
  RegionDetectionService
  RegionValidationService
  RegionTemplateService
  RegionGui
```

### 7.1 RegionRegistry

负责加载、查询、更新 region definitions。

```kotlin
class RegionRegistry(
    private val storage: RegionStorage,
    private val validator: RegionValidationService,
) {
    fun load()
    fun reload()
    fun all(): Collection<RegionDefinition>
    fun find(id: String): RegionDefinition?
    fun put(region: RegionDefinition): ServiceResult
    fun remove(id: String): ServiceResult
}
```

### 7.2 RegionDetectionService

负责检测玩家当前在哪些 region 内。

注意：一个玩家可能同时位于多个 region。需要定义冲突策略：

- 默认只允许一个 active gameplay region。
- 可以允许多个 passive region 叠加。
- Mode region 优先级高于 passive region。
- 配置 `priority` 决定重叠区域顺序。

```kotlin
class RegionDetectionService {
    fun regionsAt(location: Location): List<RegionDefinition>
    fun updatePlayer(player: Player)
}
```

### 7.3 ScopedEffectService

所有 Effect 的唯一入口。

```kotlin
class ScopedEffectService {
    fun apply(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult
    fun restoreLease(leaseId: UUID): ServiceResult
    fun restoreRegion(player: Player, regionId: String): ServiceResult
    fun cleanupPlayer(player: Player, reason: CleanupReason): ServiceResult
    fun refreshAll()
}
```

该服务要像 RuleGems `EffectConfig` 一样采用“短时长 + 周期刷新”，尤其是 potion、glowing 等状态，避免插件异常关闭后长期残留。

### 7.4 RegionTriggerService

统一执行 IF/THEN：

```kotlin
class RegionTriggerService {
    fun fire(trigger: RegionTrigger, ctx: TriggerContext)
}
```

执行流程：

```text
fire trigger
  load actions
  for each action block
    evaluate conditions
    if true execute then actions
    else execute else actions
  record failures in debug log
```

为了防止配置失控，必须限制：

- 每个 trigger 最大 action 数。
- 每个 action 最大递归深度。
- `mode_command` 不能无限调用触发器。
- `on_timer` 必须有最小间隔。

## 8. 首批 Mode 设计

### 8.1 free_event

MVP 必做。它没有复杂状态，只提供通用场地能力。

用途：

- 小人国
- 临时活动区
- 拍照区
- 规则限制区
- 轻量小游戏前置场地

配置：

```yaml
mode:
  type: free_event
  allow-join-command: false
```

### 8.2 dual_pvp

第二个推荐实现，用来验证完整 mode 生命周期。

状态：

```text
IDLE
CHALLENGING
COUNTDOWN
FIGHTING
FINISHED
```

需要：

- 双方挑战/接受。
- 入场点。
- 倒计时。
- 胜负判定。
- 死亡/离线处理。
- 离场清理。

### 8.3 parkour

适合中期实现。

需要：

- 起点。
- 检查点。
- 终点。
- 计时。
- 排行榜。
- 跌落重置。
- 禁飞/禁传送/禁末影珍珠等推荐 flag。

### 8.4 race

跑酷的泛化版本，但实体/载具复杂度更高。

配置：

```yaml
mode:
  type: race
  vehicle: boat
  laps: 3
  checkpoints:
    - id: start
    - id: turn_1
    - id: finish
```

载具类型：

- `horse`
- `boat`
- `minecart`
- `pig`
- `strider`

### 8.5 union_war

放在框架稳定后实现。

依赖：

- `UnionProvider`
- `LandsRegionSource`
- 计分系统
- 战争状态机
- 奖励结算
- PlaceholderAPI

状态：

```text
SCHEDULED
REGISTRATION
PREPARING
ACTIVE
OVERTIME
FINISHED
COOLDOWN
```

计分来源：

- 击杀。
- 占点。
- 区域停留。
- 破坏/放置指定方块。
- 护送实体。

### 8.6 hide_and_seek

适合后期作为高级样板。

它证明 action 和 mode command 的关系：

- 角色分配由 Mode 管。
- 触发器可以调用 `mode_command`。
- scale、glowing、potion 等由 Effect 管。

## 9. 权限设计

建议权限：

```text
regions.use
regions.admin
regions.reload
regions.region.create
regions.region.remove
regions.region.edit
regions.region.enable
regions.region.bind
regions.template.apply
regions.inspect
regions.cleanup
regions.bypass.limit
regions.bypass.flags
regions.mode.<mode>
regions.effect.<effect>
regions.action.<action>
```

统治者权限可以通过 RuleGems integration 判断，而不必全部落在 Bukkit permission 上。

```kotlin
interface RegionAuthorityService {
    fun canCreate(player: Player): Boolean
    fun canEdit(player: Player, region: RegionDefinition): Boolean
    fun canUseTemplate(player: Player, templateId: String): Boolean
}
```

默认策略：

- OP / `regions.admin` 可管理全部。
- RuleGems 统治者可创建和管理自己的场地。
- Lands owner/member 可作为 owner-policy 的输入。
- 每个 region 可单独授权协作者。

## 10. Lands 对接计划

MVP 对接目标：

- 检测 Lands 是否安装。
- 根据 land + area 解析区域。
- 判断玩家是否在 area 内。
- 枚举玩家可管理的 Lands areas。
- 判断 Lands owner/trusted member。

避免做：

- 不要复制 Lands 的权限规则。
- 不要把 Lands API 类型暴露给 mode/effect/action。
- 不要让 region id 直接等于 Lands 内部对象引用。

Lands 出错时：

- `LandsRegionSource.isAvailable()` 返回 false。
- 所有 Lands region 校验为 error。
- 已在线玩家相关 session 执行 cleanup。
- GUI 显示“Lands 当前不可用”。

## 11. Placeholder 与外部集成

Regions 应提供自己的 placeholders：

```text
%regions_current%
%regions_current_name%
%regions_mode%
%regions_union%
%regions_union_relation_<player>%
%regions_game_state%
%regions_game_score%
%regions_parkour_time%
%regions_race_rank%
```

外部依赖都应可选：

- Lands：区域来源、工会来源。
- RuleGems：统治者授权。
- Vault：经济 action。
- PlaceholderAPI：placeholder 输出和 condition 输入。
- LuckPerms/Vault Permission：临时权限或权限检查。

## 11.1 奖励资金与 Contract 集成

RuleGems 服务器是完全玩家自治，因此 Regions 不应该凭空生成奖励。所有 PVP、比赛、工会战、活动奖励都必须来自明确的玩家资金来源。

奖励来源建议抽象为 `RewardFundingProvider`：

```kotlin
interface RewardFundingProvider {
    val type: String
    fun isAvailable(): Boolean
    fun reserve(regionId: String, spec: RewardFundingSpec): FundingResult
    fun payout(regionId: String, outcome: RegionOutcome): FundingResult
    fun refund(regionId: String, reason: String): FundingResult
}
```

首批资金模式：

- `none`：无奖励。
- `sponsor`：第三方赞助。赞助者提前把钱或物品锁入 escrow。
- `wager`：对战双方或多方对赌。所有参赛方确认前必须先缴纳 stake。
- `contract`：绑定 Contract 插件里的合同/托管凭证，由 Contract 负责锁款、争议和结算。

`regions.yml` 示例：

```yaml
mode:
  type: union_war
  min-players: 4
  min-unions: 2
  reward-source: contract
  reward-contract: "ab12cd"
  payout:
    winner: 80
    runner-up: 20
```

Contract 集成原则：

- Regions 不直接修改 Contract 内部存储，除非 Contract 暴露稳定 API。
- Contract 负责资金托管、退款、争议和审计日志。
- Regions 只提交“比赛结果”和“结算请求”。
- 如果 Contract 不可用或合同状态不满足要求，Region mode 不允许开始。
- 工会战奖励最好用“赞助合同”或“对赌合同”，这样奖励来源、出资者和争议处理都有记录。

推荐在 Contract 插件后续暴露一个小 API：

```kotlin
interface ContractEscrowApi {
    fun canUseAsRegionFunding(contractId: String, regionId: String): FundingCheck
    fun lockForRegion(contractId: String, regionId: String): FundingResult
    fun settleRegion(contractId: String, outcome: RegionOutcome): FundingResult
    fun refundRegion(contractId: String, reason: String): FundingResult
}
```

在 API 完成前，Regions 只保留 `reward-source=contract` / `reward-contract=<id>` 配置和校验提示，不执行真实付款。

## 12. 测试计划

单元测试优先覆盖纯逻辑：

- `RegionDefinition` 解析。
- `RegionRegistry` 增删改。
- `RegionValidationService` 校验。
- `ConditionEvaluator`。
- `ActionConfig` 解析。
- `Flag` 三态决策。
- `EffectLease` 生命周期。
- `ScopedEffectService` restore 顺序。
- `RegionSessionService` enter/leave。
- `UnionProvider` fallback 行为。

集成测试/MockBukkit 可覆盖：

- 玩家进入区域时创建 session。
- 玩家离开区域时恢复 scale/fly/potion。
- 玩家掉线时 cleanup。
- `/regions reload` 清理 active leases。
- 命令 action 不使用 OP 提权。
- watchdog 能清理位置已经离开的玩家。

手动测试清单：

- 小人国模板：进入缩小，离开恢复。
- 禁飞：进入禁飞，离开恢复原 fly 状态。
- 禁隐身：进入取消或阻止 vanish，离开不影响原始合法状态。
- 死亡清理：区域内死亡后不带走 scale/potion。
- 断线重连：断线清理，重连没有残留属性。
- 插件 reload：所有在线玩家恢复正常。
- Lands 重载/卸载：Regions failsafe cleanup。

## 13. 实现阶段

### 阶段 0：项目骨架

目标：创建可编译的 Regions 插件模块。

任务：

1. 新建 `Regions/build.gradle.kts`。
2. 加入 `settings.gradle.kts`。
3. 新建 `RegionsPlugin.kt`，继承 `CubexPlugin`。
4. 新建 `plugin.yml`、`config.yml`、`regions.yml`、`templates.yml`、语言文件。
5. 接入 `ResourceFiles` 和 `MigrationRunner`。
6. 注册 `/regions` 基础命令。
7. 添加空的 `RegionRegistry`、`RegionStorage`、`RegionValidationService`。

验收：

- `./gradlew :Regions:build` 成功。
- 服务端能加载插件。
- `/regions reload` 可用。

### 阶段 1：Region + Source + Session

目标：能绑定 Lands 区域并检测进入/离开。

任务：

1. 定义 `RegionDefinition`、`RegionSourceRef`、`ExternalRegion`。
2. 实现 `RegionSourceRegistry`。
3. 实现 `LandsRegionSource`。
4. 实现 `RegionDetectionService`。
5. 实现 `RegionSessionService`。
6. 监听 move/teleport/quit/death/reload。
7. 实现 watchdog。

验收：

- 玩家进入 Lands area 后创建 region session。
- 离开后 session 被删除。
- `/regions inspect <player>` 能显示所在 region。

### 阶段 2：Effect 安全底座

目标：所有区域特殊状态都可托管、刷新、恢复。

任务：

1. 定义 `ScopedEffect`、`EffectLease`、`EffectScope`。
2. 实现 `ScopedEffectService`。
3. 实现 `potion` effect。
4. 实现 `allow_flight`、`walk_speed`、`fly_speed`。
5. 实现 `scale` effect。
6. 实现 `/regions cleanup <player>`。
7. 实现 reload/disable 全量恢复。

验收：

- 小人国 `scale=0.35` 进入生效，离开恢复。
- 插件 reload 后玩家没有残留 scale/potion/fly。
- watchdog 能回收过期或离区 lease。

### 阶段 3：Flags

目标：提供基础区域规则。

任务：

1. 定义 `RegionFlag`、`FlagContext`、`RuleResult`。
2. 实现 `pvp`。
3. 实现 `fly`。
4. 实现 `vanish`。
5. 实现 `item_drop`、`item_pickup`。
6. 实现 `commands` blocklist/allowlist。
7. 实现 flag GUI 和命令。

验收：

- 区域内禁飞/禁隐身/禁 PVP 生效。
- 离开区域后不改变玩家原始权限和状态。
- 与 Lands 原有规则不硬冲突，`PASS` 情况可交给外部插件。

### 阶段 4：Action + IF/THEN

目标：统治者可在服务器内配置触发行为。

任务：

1. 定义 `RegionTrigger`、`RegionAction`、`Condition`。
2. 实现 `RegionTriggerService`。
3. 实现 `message`、`title`、`sound`。
4. 实现 `console_command`、`player_command`。
5. 实现 `effect_apply`、`effect_clear`。
6. 实现 condition：permission、owner、ruler、union、time、metadata。
7. 实现 GUI 向导。

验收：

- 能配置“进入区域 -> 如果是统治者 -> scale 缩小并发送 title”。
- 命令 action 不会默认 OP 提权。
- action 执行失败有日志和 GUI 校验提示。

### 阶段 5：free_event MVP 完成

目标：完成第一个真正可用版本。

任务：

1. 实现 `free_event` mode。
2. 实现模板系统。
3. 内置模板：小人国、活动广场、禁飞区、拍照区。
4. 完成 Region GUI 主流程。
5. 完成文档和示例配置。

验收：

- 统治者无需编辑 YAML 即可创建小人国。
- 安全校验全部通过。
- 可以发布为 0.1.0。

### 阶段 6：dual_pvp 与 parkour

目标：验证复杂 mode 架构。

任务：

1. 实现 `dual_pvp` 状态机。
2. 实现挑战/接受/倒计时/胜负。
3. 实现 `parkour` 起点/检查点/终点/计时。
4. 添加排行榜存储。
5. 添加 mode-specific placeholders。

验收：

- dual_pvp 玩家异常退出能安全结算和清理。
- parkour 无法把禁飞、速度、计分板等状态带出区域。

### 阶段 7：UnionProvider 与 union_war

目标：实现工会战。

任务：

1. 定义 `UnionProvider`。
2. 实现 `LandsUnionProvider`。
3. 实现 `UnionWarMode` 状态机。
4. 支持报名、准备、开战、计分、结算。
5. 支持 PlaceholderAPI。
6. 支持奖励 action。

验收：

- 工会战不直接依赖 Lands 类型。
- 可未来接入 PlayerGuild 等插件。
- 战争结束后所有临时状态清理。

### 阶段 8：race 与 hide_and_seek

目标：扩展更多场地类型。

任务：

1. 实现 `race` 通用赛道 mode。
2. 支持 boat/horse/minecart。
3. 实现 `hide_and_seek` role 管理。
4. 允许 trigger 调用 mode command。
5. 完善 GUI 中 mode-specific 配置页。

验收：

- 赛道检查点和排名稳定。
- 藏猫猫角色、发光、隐身、缩放全部由 scoped effect/session 托管。

## 14. MVP 范围建议

第一版不要急着做工会战。MVP 应该把“区域内状态安全托管”做硬。

MVP 必含：

- Lands region source。
- Region 创建、绑定、启用、禁用。
- `free_event` mode。
- Flags：`pvp`、`fly`、`vanish`、`commands`。
- Effects：`scale`、`potion`、`walk_speed`、`allow_flight`。
- Triggers：`on_enter`、`on_leave`、`on_death`。
- Actions：`message`、`title`、`console_command`、`player_command`、`effect_apply`、`effect_clear`。
- Conditions：permission、ruler、owner、time。
- `/regions inspect`。
- `/regions cleanup`。
- watchdog。
- 小人国模板。

明确不做进 MVP：

- 工会战。
- 跑酷排行榜。
- 多载具赛道。
- 藏猫猫。
- 脚本语言。
- 玩家 OP 提权 action。

## 15. 关键设计决策

1. `scale` 是 Effect，不是 Action。Action 只能通过 `effect_apply` 申请 scale lease。
2. Action 主要服务 IF/THEN，适合统治者在 GUI 中配置。
3. 复杂玩法必须写成 Mode，避免把完整游戏流程塞进 YAML。
4. Flags 使用 `ALLOW/DENY/PASS`，避免和 Lands 等插件冲突。
5. 所有临时玩家状态必须通过 `ScopedEffectService`。
6. Effect 尽量“限时 + 周期重施”，降低孤儿效果风险。
7. Mode 可以有自己的命令，但仍必须通过 session/effect 服务管理玩家状态。
8. Lands 和未来 guild 插件都只在 integration/provider 层出现，玩法层只看 `RegionSource` 和 `UnionProvider`。
9. GUI 是主操作入口，YAML 是高级配置和备份格式。
10. 安全恢复和 inspect/cleanup 是 MVP 的一等功能。

## 16. 后续开放问题

- Region 重叠时，是否允许多个 active mode 同时存在？建议 MVP 禁止，只允许一个最高优先级 active mode。
- 统治者创建区域是否消耗资源或货币？可未来对接 Vault/RuleGems。
- 区域模板是否可由玩家导出/分享？建议后续支持。
- 是否要提供 HTTP/Web 管理？不进入当前计划。
- 是否要让 action 支持复杂表达式？建议先用固定 condition，避免脚本安全问题。
- Lands nation 和“工会”的映射是否一对一？先由 `LandsUnionProvider` 配置决定，mode 不关心。
