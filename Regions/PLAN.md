# Regions 插件设计方案与实现计划

## 1. 目标定位

Regions 是 CubeX 插件体系下的“特殊场地编排插件”。它不重新实现领地、选区和所有权，而是把 Lands、未来的 Residence、WorldGuard 等外部区域统一成可配置的 Region，再允许 RuleGems 的统治者为这些区域安装玩法、规则、效果和 IF/THEN 行为。

一句话定位：

> 外部领地插件负责“哪里是区域、谁拥有区域”；Regions 负责“这个区域在服务器规则中变成什么场地”。

Regions 的日常场地管理者不是普通玩家，也不是仅凭领地主身份即可使用的玩家，而是**同时满足以下两个条件**的场地主：

1. 持有 `regions.admin`，该权限由 RuleGems 的统治者身份授予。
2. 是 Region 所绑定外部区域的实际主人；MVP 中即 Lands land/area 的 owner。

服务器管理员只负责紧急接管、事故恢复和全局配置，使用独立的 `regions.superadmin`（或控制台）绕过所有权检查。`regions.admin` 在本插件中的业务含义是“统治者准入”，不能被解释为“可管理全部 Region”。

因此设计必须同时满足：

- 好理解：GUI 和命令以“区域、玩法、规则、效果、触发器”组织，不暴露太多底层细节。
- 足够灵活：简单场地靠 flag/effect/action 拼装，复杂玩法写成 mode。
- 安全可恢复：玩家在区域内获得的异常属性只能存在于区域内，离开、死亡、掉线、重载、插件关闭时必须被清理。
- 可扩展：区域来源、工会来源、玩法模式、效果、动作都通过注册表扩展。
- 权限不可绕过：GUI、命令、内部服务和未来 API 必须复用同一个授权服务，始终执行“统治者 AND 来源主人”检查。
- 能力真实可见：未实现、缺少依赖或无法安全校验的功能不得在 GUI 中伪装为可用能力。
- 可审计发布：场地修改先形成草稿，通过校验与预览后发布；关键操作可追溯、可回滚。

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
- 首次公开发布前直接确立 `config.yml`、`regions.yml`、`templates.yml` 和语言文件的当前格式基线；内部开发格式不提供迁移兼容。首次公开发布后再从该基线开始建立单向迁移链。
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
    val lifecycle: RegionLifecycle,
    val revision: Long,
    val publishedRevision: Long?,
    val mode: ModeConfig?,
    val flags: Map<String, FlagConfig>,
    val effects: List<EffectConfig>,
    val triggers: Map<RegionTrigger, List<ActionConfig>>,
    val metadata: Map<String, String>,
)
```

`enabled` 是管理员紧急开关，不代替发布状态。`lifecycle` 至少包含：

```kotlin
enum class RegionLifecycle {
    DRAFT,
    PUBLISHED,
    FROZEN,
    ARCHIVED,
}
```

- `DRAFT`：可编辑、可校验、可试运行，但不对普通玩家生效。
- `PUBLISHED`：以不可变的已发布 revision 对外运行；继续编辑产生新的草稿 revision。
- `FROZEN`：领地转让、统治者身份丢失、Source 不可用或管理员冻结时停止开赛和编辑。
- `ARCHIVED`：保留历史和审计记录，但不参与检测。

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

Region 不把缓存的玩家 UUID 当作最终所有权依据。每次编辑、发布、开赛等关键操作都必须向 `RegionSource` 重新确认当前 owner，防止领地转让后旧主人继续操作。

### 3.2 Source

Source 负责连接外部区域系统。

```kotlin
interface RegionSource {
    val type: String
    fun isAvailable(): Boolean
    fun resolve(ref: RegionSourceRef): ExternalRegion?
    fun contains(ref: RegionSourceRef, location: Location): Boolean
    fun getOwnedRegions(playerId: UUID): List<ExternalRegion>
    fun isOwner(ref: RegionSourceRef, playerId: UUID): Boolean
}
```

`getOwnedRegions` 服务于创建向导和列表展示，`isOwner` 服务于每次服务端授权判断。两者必须以 owner 为准，trusted member、租客或普通成员不能自动获得 Regions 管理权。

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

- 直接使用当前 Paper 基线提供的原生 Attribute 或 API，不再为旧 Spigot 版本增加反射降级路径。
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

### 3.9 Capability Descriptor

Mode、Flag、Effect、Action、Condition 和 Source 不能只注册一个字符串。每项能力都应通过 descriptor 描述自身，供校验、GUI、权限和扩展 API 共用：

```kotlin
data class CapabilityDescriptor(
    val id: String,
    val displayNameKey: String,
    val descriptionKey: String,
    val status: CapabilityStatus,
    val risk: CapabilityRisk,
    val parameters: List<ParameterDescriptor>,
    val requiredPlugins: Set<String>,
    val permission: String?,
)
```

Descriptor 至少声明参数类型、必填性、默认值、范围、枚举值、依赖、风险等级和运行状态。GUI 根据 descriptor 动态生成与当前玩法有关的编辑项，校验器也使用同一份 schema，避免 GUI、YAML 和运行时各维护一套规则。

能力状态约束：

- `STABLE`：可以创建和发布。
- `BETA`：必须明确提示风险，可由全局配置决定是否允许发布。
- `UNAVAILABLE`：缺少依赖或服务端不支持，只能展示原因，不能保存为已发布配置。
- `NOT_IMPLEMENTED`：开发占位，不出现在普通管理 GUI。

未知 Condition、Action、Flag、Effect 或 Mode 必须校验失败并拒绝发布；运行时也必须 fail closed，绝不能把未知 Condition 当作 `true`。

## 4. 安全模型

安全模型是 Regions 的核心功能，不是附属功能。

原则：

1. 所有区域临时状态必须通过 `ScopedEffectService` 申请 lease。
2. Mode、Action、Flag 不得直接留下不可追踪的玩家属性修改。
3. 玩家离开区域后必须恢复到进入前或插件接管前的状态。
4. 若无法恢复，必须记录日志并提供 `/regions cleanup` 人工修复。
5. 尽量使用“限时 + 周期重施”，不要依赖永久效果。
6. 所有写操作和玩法控制操作必须在 service 层再次授权，不能只依赖 GUI 或命令入口检查。
7. 运行中的场地只读取不可变的已发布 revision，编辑草稿不能中途改变比赛规则。
8. 未实现或校验失败的能力不得进入运行态。

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

- `console_command`：仅 `regions.superadmin` 可以发布，由控制台执行并只支持白名单变量替换；普通统治者即使同时是 Source owner 也不能发布。
- `player_command`：玩家本人执行，不提权。
- 不提供默认 `player-op`。
- 如果未来提供提权执行，必须全局配置显式开启，并在启动时输出高危警告。
- 命令变量采用 `{player}`、`{uuid}`、`{region}`、`{union}`，替换后必须移除前导 `/`。
- GUI 中对高危 action 显示警告。
- `console_command` 每次执行记录 Region、命令根和目标玩家 UUID，不把完整敏感参数写入审计。

### 4.5 重叠区域与组合规则

Region 重叠必须有确定、可解释的结果，不能依赖监听器执行顺序：

- 同一玩家同时只允许一个有状态 Mode 生效；按显式 priority 选择，冲突时拒绝发布或拒绝进入。
- Flag 按 priority 从高到低取第一个非 `PASS` 结果，并能在 GUI 中解释“最终值来自哪个 Region”。
- Effect 必须声明组合策略：`EXCLUSIVE`、`HIGHEST_PRIORITY`、`STACK` 或 `MERGE_BY_TYPE`。
- Trigger 默认对所有 active Region 执行，但高风险 Action 可以声明只允许主 Region 执行。
- 发布预览必须显示当前位置或选定区域的“最终有效规则”，而不只显示单个 Region 的原始配置。

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

governance:
  ruler-permission: regions.admin
  superadmin-permission: regions.superadmin
  require-source-owner: true
  freeze-on-owner-change: true
  allow-trusted-members: false

publishing:
  require-validation: true
  require-preview: true
  keep-revisions: 20
  allow-beta-capabilities: false

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
    lifecycle: published
    revision: 4
    published-revision: 3
    source:
      type: lands
      land: capital
      area: miniature
    owner-policy: ruler_and_source_owner
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

模板不是简单的 YAML 复制片段，而是一份可校验、可预览、可升级的场地产品定义。每个模板应包含：

- 稳定 id、显示名、说明、分类和模板版本。
- 适用的 Source、所需插件和所需能力。
- 可由统治者填写的参数 schema，例如人数、检查点、装备和奖励来源。
- 默认 Mode、Flag、Effect、Trigger，以及不可被普通统治者开启的高风险项。
- 应用策略：新建、覆盖、合并或仅追加规则包。

`RegionTemplateService` 必须提供加载、校验、预览 diff、应用和升级能力。模板应用只修改草稿；统治者确认 diff 并通过校验后才能发布。模板版本升级不得静默覆盖已经发布的场地。

```yaml
templates-version: 1

templates:
  mini_kingdom:
    version: 1
    name: "小人国"
    description: "玩家进入后缩小，禁飞、禁隐身、禁 PVP。"
    category: social
    parameters: {}
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

统治者不应该被迫理解 YAML、Region 内部 id 或 `key=value`。命令和 GUI 要围绕“把自己的领地发布为场地”设计。

任何管理页面都只展示当前统治者实际拥有的 Source。服务器管理员需要查看全部 Region 时，使用独立的超级管理员入口，避免把日常统治者界面变成全服后台。

### 6.1 命令

基础命令：

```text
/regions
/regions list [mine|active]
/regions create
/regions draft <id>
/regions publish <id>
/regions unpublish <id>
/regions history <id>
/regions rollback <id> <revision>
/regions remove <id>
```

超级管理员命令单独分组：

```text
/regions admin list --all
/regions admin freeze <id>
/regions admin unfreeze <id>
/regions admin reload
/regions admin cleanup <player>
```

配置命令：

```text
/regions mode set <id> <mode>
/regions flag set <id> <flag> <value>
/regions effect add <id> scale <value>
/regions effect remove <id> <effect-id>
/regions trigger add <id> <trigger>
/regions template apply <id> <template>
/regions preview <id>
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
  MyRegionListMenu
  OwnedSourcePickerMenu
  CreateWizardMenu
    TemplateCatalogMenu
    TemplateParameterMenu
    DraftSummaryMenu
  RegionDetailMenu
    SourceBindMenu
    ModeMenu
    FlagMenu
    EffectMenu
    TriggerMenu
    EffectiveRulesMenu
    ValidateMenu
    PublishPreviewMenu
    RevisionHistoryMenu
    DebugMenu
  TemplateMenu
  SuperAdminMenu
```

统治者视角的核心流程：

1. `/regions` 打开 GUI。
2. 点击“创建场地”，系统只列出自己拥有的 Lands land/area。
3. 选择一个尚未绑定或允许复用的 area。
4. 选择模板，例如“小人国”或“划船赛”。
5. 只填写该模板要求的关键参数；Mode 页面不显示其他玩法的参数。
6. 生成草稿，预览模板 diff、最终有效规则、依赖和风险提示。
7. 在隔离的试运行状态下验证入场、离场和清理。
8. 校验通过后发布；发布时再次确认统治者身份和 Source owner。
9. 后续编辑生成新 revision，不直接改变正在运行的玩法。

玩家进入已发布场地时，`/regions` 应优先显示当前场地名称、规则、状态和适用操作，例如“准备、退出、查看成绩”，而不是要求玩家记住 Region id 和完整的 `/regions game <id> ...`。

GUI 采用渐进披露：常用设置用模板和结构化控件完成，高级页面再暴露 descriptor 中允许编辑的参数。原始 `key=value` 只保留给超级管理员和调试用途。

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
  RegionAuthorityService
  RegionLifecycleService
  RegionRevisionStore
  RegionAuditService
  CapabilityCatalog
  RegionOverlapResolver
  RegionGui
```

授权、校验、发布和审计必须位于 service 层。GUI、命令和未来 API 都只是调用服务，不能分别实现自己的权限逻辑。

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
regions.superadmin
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

`regions.admin` 是 RuleGems 统治者的准入权限，不代表可以管理全服 Region。为了兼容现有权限配置可以保留该节点名称，但所有管理操作都必须继续检查绑定 Source 的 owner。

`regions.superadmin` 仅授予服务器运维人员，允许查看全部 Region、冻结场地、执行恢复和绕过 owner 检查。控制台等同于 superadmin。不要把 `regions.admin` 设为 `regions.superadmin` 的子权限。

```kotlin
interface RegionAuthorityService {
    fun isRuler(player: Player): Boolean
    fun isSuperAdmin(sender: CommandSender): Boolean
    fun canCreate(player: Player, source: RegionSourceRef): AuthorityDecision
    fun canView(player: Player, region: RegionDefinition): AuthorityDecision
    fun canEdit(player: Player, region: RegionDefinition): AuthorityDecision
    fun canPublish(player: Player, region: RegionDefinition): AuthorityDecision
    fun canControlGame(player: Player, region: RegionDefinition): AuthorityDecision
    fun canUseTemplate(player: Player, templateId: String): Boolean
}
```

`AuthorityDecision` 应携带稳定的拒绝原因，例如 `NOT_RULER`、`NOT_SOURCE_OWNER`、`SOURCE_UNAVAILABLE`、`REGION_FROZEN`，用于 GUI、命令、日志和测试共用。

默认策略：

- 创建：`regions.admin` AND 是所选 Source 的实际主人。
- 查看、编辑、发布和控制玩法：`regions.admin` AND 是该 Region 当前绑定 Source 的实际主人。
- trusted member、租客、普通 Lands member 单独存在时不能管理 Regions。
- `regions.superadmin`、控制台可以紧急绕过，但必须写入审计日志。
- RuleGems 统治者身份或 Source owner 任一条件丢失后，Region 进入 `FROZEN`；运行中的 Mode 安全结束，已发布配置和历史不删除。
- 重新满足两个条件后可以解冻；领地转让不会把旧主人的草稿和控制权继续保留下来。
- Cuboid Source 默认只允许 superadmin 创建和管理；若未来允许统治者使用，必须先定义可信的所有权来源，不能用“创建者 UUID”替代真实领地所有权。

授权检查必须覆盖创建、列表过滤、查看详情、编辑、删除、模板应用、发布、启停和玩法控制。仅在 GUI 打开时检查一次是不够的，每个写操作都要重新检查。

## 10. Lands 对接计划

MVP 对接目标：

- 检测 Lands 是否安装。
- 根据 land + area 解析区域。
- 判断玩家是否在 area 内。
- 枚举玩家实际拥有的 Lands land/area，用于创建向导。
- 精确判断当前 Lands owner；trusted member 与 owner 必须区分。
- 处理领地改名、area 改名、转让、删除和 Lands 重载。
- 为关键授权检查提供可主动失效的短期缓存，不能长期缓存 owner 结果。

避免做：

- 不要复制 Lands 的权限规则。
- 不要把 Lands API 类型暴露给 mode/effect/action。
- 不要让 region id 直接等于 Lands 内部对象引用。

Lands 出错时：

- `LandsRegionSource.isAvailable()` 返回 false。
- 所有 Lands region 校验为 error。
- 已在线玩家相关 session 执行 cleanup。
- GUI 显示“Lands 当前不可用”。
- 相关 Region 进入 `FROZEN`，恢复后重新校验 owner 才能解冻。

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

## 11.2 发布、审计与所有权变更

自治服务器中的场地规则会影响其他玩家，因此每次发布都必须形成不可变 revision 和审计事件：

```kotlin
data class RegionAuditEvent(
    val id: UUID,
    val regionId: String,
    val actorId: UUID?,
    val action: String,
    val fromRevision: Long?,
    val toRevision: Long?,
    val reason: String?,
    val createdAt: Instant,
)
```

至少记录：创建、模板应用、配置修改、发布、撤回、回滚、冻结、解冻、强制结束、cleanup、比赛结果和资金结算。高风险 Action 的配置和执行也应记录摘要，但不得把敏感命令参数或玩家隐私写入公开日志。

发布事务：

1. 重新检查统治者权限和 Source owner。
2. 校验 Source、Mode、Flag、Effect、Condition、Action、依赖与重叠冲突。
3. 生成草稿与当前已发布 revision 的 diff。
4. 将草稿固化为新的不可变 revision。
5. 原子切换 `publishedRevision`，再通知运行时刷新。
6. 写入审计事件；失败时保留旧 revision 继续运行。

领地转让或身份变化时不删除 Region。系统冻结它、终止新的报名与开赛、安全结束运行态，并保留历史以便新主人重新配置或超级管理员处理。

## 12. 测试计划

当前自动化基线（2026-07-16）：12 个测试套件、44 个测试全部通过，0 failure、0 error、0 skipped。

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
- `RegionAuthorityService` 的统治者/owner/superadmin 权限矩阵。
- 未知或未实现 Capability 必须校验失败，未知 Condition 不得默认通过。
- 草稿、发布、回滚和 revision diff。
- 重叠 Region 的 Mode、Flag 和 Effect 合并策略。

集成测试/MockBukkit 可覆盖：

- 玩家进入区域时创建 session。
- 玩家离开区域时恢复 scale/fly/potion。
- 玩家掉线时 cleanup。
- `/regions reload` 清理 active leases。
- 命令 action 不使用 OP 提权。
- watchdog 能清理位置已经离开的玩家。
- 统治者只能看到并编辑自己实际拥有的 Lands area。
- 只有 ruler 或只有 owner 时都不能创建、编辑、发布或开赛。
- 领地转让、统治者身份丢失和 Source 不可用时自动冻结。
- GUI、命令和 service 直接调用得到一致的授权结果。
- 发布失败不会替换正在运行的旧 revision。

手动测试清单：

- [x] 全新 Paper 1.21.11 服务端首次启动，生成首发默认文件并以 0 个 Region 正常启用。
- [x] 已生成数据再次启动、执行 `/regions reload` 并正常停服，未出现 Regions 错误或清理异常。
- [x] 真实 Paper 控制台完成 Cuboid 草稿创建、绑定、Mode/Flag/Effect 编辑、完整预览、发布、撤回、再次发布、历史回滚、reload 和停服。
- 小人国模板：进入缩小，离开恢复。
- 禁飞：进入禁飞，离开恢复原 fly 状态。
- 禁隐身：进入取消或阻止 vanish，离开不影响原始合法状态。
- 死亡清理：区域内死亡后不带走 scale/potion。
- 断线重连：断线清理，重连没有残留属性。
- 插件 reload：所有在线玩家恢复正常。
- Lands 重载/卸载：Regions failsafe cleanup。
- 使用模板完成“选择自己的 area -> 预览 -> 试运行 -> 发布”的完整流程。
- 领地转让后旧主人立即失去管理权，新主人不会继承未发布草稿。
- superadmin 紧急接管会留下可查询的审计记录。

## 13. 当前基线与后续实施路线

阶段 0-8 是最初的建设顺序，保留用于理解架构来源。当前代码已经覆盖其中相当一部分，但“已注册”不等于“已完整实现”，后续开发不能仅按旧阶段编号判断完成度。每次发布前都应依据 Capability Descriptor、自动化测试和手动验收重新盘点。

### 当前平台基线：Paper 原生化

Regions 以 **Java 21 + Paper 1.21.11** 为唯一服务端基线，不再声明 Bukkit API 或 Spigot API artifact。代码中继续出现的 `org.bukkit.*` 包属于 Paper API 继承的公共服务端接口，并不表示重新引入 Bukkit/Spigot 依赖。

已完成：

- [x] 编译依赖仅使用 `paper-api`，编译版本、`api-version` 与本地 `runServer` 统一为 1.21.11。
- [x] 消息、GUI 标题、物品名称/说明与 title action 全部迁移到 Adventure `Component`。
- [x] GUI 文本输入迁移到 Paper `AsyncChatEvent`，不再监听旧 `AsyncPlayerChatEvent`。
- [x] Sound、Attribute 与 PotionEffect 查询改用现代常量或 Registry key，不再调用旧枚举名称查找。
- [x] 战斗装备托管使用 Paper `ItemStack.serializeAsBytes`；首次公开发布不接受内部开发阶段的旧对象流格式。
- [x] 保留 Folia 调度适配并继续声明 `folia-supported: true`；发布 JAR 已将 FoliaLib relocation 到 Regions 私有命名空间，避免与其他插件冲突。
- [x] 发布 JAR 已在 Paper 1.21.11 build 132 上完成首次启动、再次启动、命令 reload 和正常关闭冒烟验证。

后续平台增强（不阻塞首轮真人验证）：

- [x] 根命令通过 Paper Lifecycle Command API 注册，旧 `plugin.yml` command executor 已移除。
- [ ] 仓库整体升级到 Gradle 8.14.3+ 后，将共享 `run-paper` 从 2.x 升级到 3.x；这是仓库工具链工作，不是 Regions 运行能力缺口。直接升级已确认会被当前 Gradle 8.8 的 Plugin API 版本阻止，Regions 已改用官方 Paper JAR 完成独立真实启动验证。
- [ ] 将各子命令升级为强类型 Brigadier 节点，进一步提供参数约束和客户端可见的完整命令树；当前 Lifecycle Command API、权限过滤与参数补全足以支持首轮真人验证。
- [x] 首发版本继续使用 Inventory GUI 作为模板参数、Mode 编辑和发布预览的稳定入口；Paper Dialog API 留作发布后的可选增强，不作为首发依赖。
- [ ] 按 `REAL_PLAYER_TEST.md` 完成 GUI 创建向导、玩家进出、异常关服、装备托管恢复，并在真实 Folia 上重复关键流程。

### 首次公开发布的数据基线

Regions 尚未公开发布，因此当前格式直接作为首个公开版本基线，不为开发阶段产生的旧配置和数据增加维护成本。

- [x] `config-version: 4`、`regions-version: 4`、`templates-version: 1`、`lang-version: 4` 被定义为首发基线。
- [x] 战斗与回合装备托管使用 `escrow-version: 1` 和 Paper 字节格式；未知或损坏格式拒绝加载，避免静默丢失玩家库存。
- [x] 启动与 reload 会校验所有基线版本；发现内部开发旧文件时明确拒绝加载并要求重新生成或人工更新。
- [x] 默认资源、运行时保存版本与基线保持一致，不再出现目标版本和迁移链不一致。
- [ ] 第一个公开版本发布后冻结这些基线；未来任何格式变化必须提供从公开版本到新版本的单向迁移和自动化测试。

### 阶段 0：项目骨架

目标：创建可编译的 Regions 插件模块。

任务：

1. 新建 `Regions/build.gradle.kts`。
2. 加入 `settings.gradle.kts`。
3. 新建 `RegionsPlugin.kt`，继承 `CubexPlugin`。
4. 新建 `plugin.yml`、`config.yml`、`regions.yml`、`templates.yml`、语言文件。
5. 接入 `ResourceFiles` 和首发格式基线校验；公开发布后再启用正式迁移链。
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

### 后续阶段 A：授权与能力真实性

目标：先确保只有正确的人能管理正确的场地，并且界面展示的能力都真实可用。

实现进度（2026-07-16）：阶段 A 的统一授权、owner 判断、能力真实性和基础测试已经完成。阶段 B 已完成 Lands owned-area 安全选择、TemplateService/参数 schema、内置模板创建流程、`DRAFT/PUBLISHED/FROZEN/ARCHIVED` 工作流、不可变 revision 历史、发布/撤回/回滚、所有权变化自动冻结和基础审计。首个公开版本的数据格式基线已经确立，内部开发旧格式不进入兼容范围；正式发布后才将其冻结为必须迁移的公共契约。结构化 revision diff、依赖与最终有效规则预览、确定性的 Mode/Flag/Effect 决策、Effect 四种组合策略与作用域、Trigger 主区域边界、可提供几何的 Source 间冲突预判、个人隔离试运行及失败原子回滚、标准模板 `if/then` 解析、Mode 专用 GUI、比赛结果审计、superadmin-only `console_command`、写入失败恢复和 Paper 真实启动/reload/关闭冒烟验证已经完成。代码层面的真人验证前阻断项已收口；全面动态错误国际化、强类型 Brigadier 与仓库 run-paper 升级属于后续体验/工具链增强，真实 Folia 和真人玩法端到端测试进入下一阶段。

任务：

- [x] 实现 `RegionAuthorityService`，统一执行“`regions.admin` AND Source owner”。
- [x] 增加独立 `regions.superadmin`，移除 `regions.admin` 的全局管理语义。
- [x] GUI、命令、Service 和玩法控制全部接入同一授权服务。
- [x] 为 Source、Mode、Flag、Effect、Condition、Action 建立 Capability Descriptor 和参数 schema。
- [x] 未实现能力隐藏或标记不可用；未知 Condition 改为 fail closed。
- [x] 补齐授权矩阵、领地转让和能力校验测试。

验收：

- [x] 只有 ruler 或只有 owner 时都无法管理 Region。
- [x] 一个统治者无法查看、编辑或控制另一个统治者的场地。
- [x] superadmin 的变更、强制操作和生命周期操作会留下审计记录。
- [x] GUI 中不存在“可保存但运行时无实现”的稳定能力。

### 后续阶段 B：模板化创作与发布

目标：让符合资格的统治者不编辑 YAML、不输入内部 id，也能安全发布自己的场地。

任务：

- [x] 实现 `RegionTemplateService` 和模板参数 schema。
- [x] 从 Lands API 直接选择自己拥有的 land/area，并在保存前再次校验 owner。
- [x] 实现“ID/名称 → owned area → 内置模板 → 校验并创建”的基础创建向导。
- [x] 完成按 Mode 动态显示的配置页、模板参数输入流程和最终有效规则完整预览。
- [x] 建立 `FROZEN` 持久化状态；冻结时停止运行态并清理场地会话，所有权变化自动冻结，只允许 superadmin 复核解冻。
- [x] 完成 `DRAFT/PUBLISHED/ARCHIVED` 的创作、发布、撤回和归档工作流。
- [x] 实现不可变 revision、发布、撤回与回滚；历史按配置限制保留数量，`regions.yml` 通过临时文件原子替换保存。
- [x] 实现结构化草稿 diff，并在 GUI 发布确认页展示；超长 diff 显示摘要但仍完整校验。
- [x] 实现个人隔离试运行：仅对发起者应用草稿 Effect 与 Flag，不启动 Mode、不执行 Trigger、不影响其他参与者。
- [x] 建立基础 `RegionAuditService`，持久化记录创建、编辑、强制操作、冻结与解冻。
- [x] 审计覆盖比赛结束原因、参与人数、规则 revision、赛跑名次与回合结果；未来接入资金系统时再追加结算事件。

验收：

- [x] 统治者可从自己的 Lands area 选择内置模板、保存草稿并显式发布场地。
- [x] 发布前可在 GUI 与 `/regions preview` 中看到 diff、依赖、冲突、主 Mode、主 Trigger Region、最终 Flag 与 Effect。
- [x] 修改草稿不会改变正在运行的已发布 revision。
- [x] 发布失败或回滚失败不会破坏当前有效版本。

### 后续阶段 C：运行时完整度与组合规则

目标：消除字符串注册表和实际运行能力之间的落差。

任务：

- [x] 实现或移除尚无监听器/执行器的 Flag 与 Action，稳定注册表只暴露真实能力。
- [x] 为人数上下限、赛跑起终点/检查点格式、捉迷藏计时关系增加发布前跨字段校验。
- [x] 补齐装备格式/数量、载具枚举、检查点载具数量、工会数量、寻找者数量与奖励来源能力真实性校验；未实现奖励会直接阻止发布。
- [x] 实现 `RegionOverlapResolver` 的确定性排序、Mode 主区域选择和 Flag 首个非 `PASS` 决策；同优先级按 Region id 稳定决胜。
- [x] 运行时只激活最高优先级的一个有状态 Mode，并在优先级变化时安全交接。
- [x] 发布/回滚时拦截同一 Source 上的多个有状态 Mode，并在预览中说明冲突 Flag 的最终来源。
- [x] 实现 `TIMED` lease 到期恢复和 `UNTIL_MODE_END` 在玩法结束时恢复；离区/reload/disable 继续清理全部 scope。
- [x] 为 Effect 声明并实现 `EXCLUSIVE`、`HIGHEST_PRIORITY`、`STACK`、`MERGE_BY_TYPE` 组合策略，并以确定的应用/逆序恢复顺序运行。
- [x] Trigger block 可声明全部 active Region 或主 Region 执行；相同 Source、Cuboid 及其他提供 geometry 的 Source 支持发布期重叠预判，未知几何会在预览中明确警告。
- [x] 完成 mode-specific GUI，普通统治者只看到当前 Mode 相关设置；原始 `key=value` Mode 编辑仅向 superadmin 保留。
- [x] 发布阻断错误会指出具体能力、字段、依赖或冲突来源，并给出可操作的修改方向。
- [ ] 将所有动态校验和第三方依赖错误完整迁入语言文件；首轮真人验证先收集实际难懂文案，再统一调整翻译键和措辞。

验收：

- 每个 `STABLE` 能力都有运行实现、校验和测试。
- 重叠 Region 的最终结果稳定且可在 GUI 中解释。
- 配置错误在发布前被发现，不依赖运行时日志兜底。

### 后续阶段 D：自治活动与可信结算

目标：让场地承载可追溯的玩家活动，而不只是一组区域效果。

任务：

1. 增加活动排期、报名、准备、开赛、结果和归档状态。
2. 对接 Contract 托管，支持赞助、对赌、退款和自动结算。
3. 记录参赛名单、规则 revision、结果、强制操作和结算摘要。
4. 提供当前场地、活动状态、成绩和资金状态 placeholders。
5. 为异常退出、平局、取消和争议建立明确结算策略。

验收：

- 奖励不会凭空生成，资金来源和去向可追溯。
- 比赛结果始终关联当时使用的规则 revision。
- 取消、插件故障和 Source 失效时可以安全退款或等待人工处理。

### 后续阶段 E：扩展生态

目标：在核心治理和发布模型稳定后开放更多区域来源与能力扩展。

任务：

1. 发布稳定的 Source、Mode、Flag、Effect、Condition、Action 注册 API。
2. 接入 Residence、WorldGuard 等 Source。
3. 支持模板导出、导入、签名和版本兼容检查。
4. 为第三方扩展提供 descriptor、权限、迁移和测试契约。

新 Mode 和新 Source 不应抢在阶段 A-C 之前扩张，否则只会放大权限、校验和 GUI 的历史债务。

## 14. 下一可发布里程碑建议

下一里程碑建议定义为“治理与创作体验版”，目标不是继续增加 Mode，而是使现有能力能够安全地交给符合资格的统治者使用。

必须包含：

- [x] `RegionAuthorityService` 与完整的 ruler AND owner 权限矩阵。
- [x] `regions.superadmin` 紧急接管和基础审计。
- [x] Lands owned area 选择器，GUI 不再通过手输名称绑定领地，保存前会复核主人身份。
- [x] Capability Descriptor、稳定能力强类型参数校验和未知能力 fail closed。
- [x] TemplateService、参数 schema、8 个内置模板及应用前规则摘要。
- [x] 提供草稿与当前发布 revision 的结构化配置 diff 和发布确认页。
- [x] 将同一 Source 上可静态确定的 Mode/Flag 重叠冲突合并进发布预览。
- [x] 将依赖、跨 Source 几何覆盖情况、Effect/Trigger 组合和完整最终有效规则合并进发布预览。
- [x] 草稿、发布、不可变 revision、撤回、归档与回滚。
- [x] 按当前 Mode 动态隐藏无关设置，并提供最终有效规则预览。
- [x] 对现有 Flag、Effect、Action、Condition 进行实现完整度清点；未完成项不出现在稳定能力中。
- [x] 普通统治者不能发布 `console_command`；仅 superadmin 可确认发布，执行只审计命令根和玩家 UUID。
- [x] Region id 在 Service 层统一限制为 `[a-z0-9_-]{2,48}`，避免命令入口绕过 GUI 并污染 YAML 路径。
- [x] 发布相关写入失败会返回失败并恢复磁盘中的旧状态；`regions.yml`、审计与装备托管使用临时文件替换。
- [x] Effect 的 `TIMED` 与 `UNTIL_MODE_END` 基础生命周期已接入运行时。
- [x] 在真实 Paper 1.21.11 上验证首次启动、默认数据生成、再次启动、`/regions reload` 和正常关闭，发布包无依赖隔离错误。
- [x] 真实 Paper 控制台完成创建、编辑、预览、发布、撤回、再次发布、历史回滚与 reload 端到端测试。
- [ ] 按 `REAL_PLAYER_TEST.md` 验证玩家进出/死亡/断线状态清理、隔离试运行、装备托管恢复、Lands/RuleGems 授权和多人 Mode 流程。

明确不进入该里程碑：

- 新增更多 Mode。
- Residence、WorldGuard 等新 Source。
- 普通领主或非统治者管理 Region。
- 协作者角色系统；当前坚持 owner 本人管理。
- 模板公开市场和 Web 管理。
- 未完成 Contract API 前的真实资金结算。
- 脚本语言。
- 普通统治者可发布的控制台或玩家 OP 提权 action。

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
11. 日常管理权限永远是 `regions.admin`（RuleGems 统治者）AND 当前 Source owner；两者缺一不可。
12. `regions.superadmin` 只用于服务器运维和紧急接管，与统治者权限分离。
13. trusted member、租客和普通成员不能替代 owner。
14. 未知或未实现能力 fail closed，不能因为已注册字符串就视为可发布。
15. 编辑发生在草稿 revision，运行时只读取不可变的已发布 revision。
16. 模板是统治者的主要创建入口，原始参数编辑是高级能力。
17. 领地转让或统治者身份丢失时冻结并保留历史，不自动删除，也不让旧主人继续控制。
18. 所有权、发布、强制操作、比赛结果和资金结算必须可审计。
19. 首次公开发布前不兼容内部开发格式；公开发布后的数据版本才形成必须维护的迁移契约。
20. `console_command` 属于服务器级权力，只允许 superadmin 发布，不能由“统治者 AND owner”授权自动获得。

## 16. 后续开放问题

- RuleGems 是否只通过 Bukkit permission 表达统治者身份，还是还要提供 API 二次确认？授权服务需要稳定契约。
- Lands 对 land owner 与 area owner/管理者的定义是否完全一致？MVP 必须明确“主人”的唯一判定规则。
- 统治者创建区域是否消耗资源或货币？可未来对接 Vault/RuleGems。
- 同一 Lands area 是否允许发布多个 Region；若允许，应如何选择主 Mode 和计费配额？
- 新主人接管被冻结 Region 时，是只继承已发布配置，还是也可选择继承旧主人的草稿？默认建议不继承草稿。
- 区域模板是否允许统治者导出/分享？建议阶段 E 支持签名与能力兼容检查后再开放。
- 是否要提供 HTTP/Web 管理？不进入当前计划。
- 是否要让 action 支持复杂表达式？建议先用固定 condition，避免脚本安全问题。
- Lands nation 和“工会”的映射是否一对一？先由 `LandsUnionProvider` 配置决定，mode 不关心。
