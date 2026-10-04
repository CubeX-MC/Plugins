<div align="center">
  <h1>Regions</h1>
  <p>可发布场地与小游戏框架</p>
  <p>
    <a href="https://github.com/CubeX-MC/Regions"><img src="https://img.shields.io/github/stars/CubeX-MC/Regions?style=flat-square&logo=github&label=Stars" alt="GitHub Stars"></a>
    <a href="https://github.com/CubeX-MC/Regions/network/members"><img src="https://img.shields.io/github/forks/CubeX-MC/Regions?style=flat-square&logo=github&label=Forks" alt="GitHub Forks"></a>
    <a href="https://github.com/CubeX-MC/Regions/issues"><img src="https://img.shields.io/github/issues/CubeX-MC/Regions?style=flat-square&label=Issues" alt="GitHub Issues"></a>
    <img src="https://img.shields.io/badge/Java-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21+">
    <img src="https://img.shields.io/badge/Paper-1.21.11%2B-5D8AA8?style=flat-square" alt="Paper 1.21.11+">
    <img src="https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square" alt="Folia">
  </p>
  <p>
    <a href="https://github.com/CubeX-MC/Regions">项目主页</a>
    ·
    <a href="https://github.com/CubeX-MC/Regions/issues">问题反馈</a>
  </p>
</div>

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

Contract 同样只是可选连接。`dual_pvp` 和 `union_war` 可在 Mode 中设置 `reward-source: contract` 与 `reward-contract: <WAGER id>`；发布及开赛前会确认该 WAGER 已由双方接受且仍在进行。Contract 缺席或状态不符时只阻止这个有奖励的场地发布/开赛，Regions 其他场地照常运行。`free_for_all` 不接奖励，设置 `reward-source` / `reward-contract` 会在校验阶段被拒绝，而不是静默忽略。

## 构建与自动检查

2026-09-23 候选包通过 380 项自动化测试及 JAR 门禁，并完成隔离 Paper 加载、重载和停服验证。
实际 Lands/Folia、多客户端比赛和真实进程崩溃恢复验收仍待执行；证据见 [本轮验证记录](docs/completion-2026-09-23.md)。

```text
./gradlew :Regions:test
./gradlew :Regions:build
./gradlew :Regions:shadowJar
./gradlew :Regions:runServer
```

可部署产物是 `Regions/build/libs/regions-<version>.jar`；不要部署 `*-plain.jar`。根仓库 CI 会单独构建、测试并上传 Regions，同时支持 `regions-v<version>` 标签发布。

`runServer` 是 R1 托管连接的本地联合服：它会装入 Contract 的部署 jar、Vault 与 EssentialsX，
但不会给 Regions 增加任何编译期插件依赖。用
`./gradlew :Regions:runServer -PregionsRunWithContract=false` 可在独立的
`Regions/run-no-contract` 目录验证 Contract 缺席时的降级与 lease 保全。

## 管理流程

日常场地变更遵循：创建草稿 → GUI/命令编辑 → `validate`/`preview` → 隔离 `trial` → `publish`。运行时只读取已发布 revision；回滚会生成新 revision，不覆盖历史。

场地详情页分两层：基础页只放场地主日常的 5 个操作（玩法设置、场地点位、检查并发布、比赛管理、隔离试运行），规则组合、临时效果、触发动作、完整 diff、历史、启停、撤回与删除在“高级设置”页里。

已有场地也可以在详情页点击“应用模板”重新选择预设。确认后，模板会整体替换草稿中的 Mode、Flags、Effects 与 Triggers，不会把上一个模板的提醒或效果带过去；Region ID、名称、来源、所有权、优先级和版本历史保持不变。重新预览并发布前，运行态不会变化。

常用入口：

- `/regions gui`：管理界面
- `/regions validate <id>`：发布前验证
- `/regions preview <id>`：查看 diff、依赖和重叠解析
- `/regions trial <id>`：仅对操作者应用草稿效果
- `/regions publish <id>`：发布 revision
- `/regions inspect <玩家>`：查看会话与租约
- `/regions cleanup <玩家>`：事故恢复
- `/regions game <id> teams [编号|名字] [编号|名字]`：锁定工会战本场的两个 Nation（报名前设置）。
  **不带参数**会列出当前对阵与带编号的可选工会；参数可以是编号、工会名（自动去颜色码）、
  唯一的名字前缀，或者旧的 ULID。名字重复时会列出候选让你选，不会替你猜。
  还可以写 `me`（或 `edit`）代表**你当前用 `/l edit` 选定领地所属的国家**；
  只写一个参数（`teams <对手>`）时甲方自动取这个国家。领地不属于任何国家就会如实报错，不会拿别的领地冒充

权限以 `plugin.yml` 为准。常规管理需要治理权限和来源所有权同时满足；`regions.superadmin` 仅用于紧急接管。

## 玩家入口

- `/regions`：打开活动大厅（只列已发布且启用的场地；不可报名的卡片直接显示原因）。
  **玩家侧的事情在这里都能点完**：正在参赛时有“我的比赛”直达按钮；
  `locale-mode: player` 时右下角有语言按钮（中文 → English → 自动循环，整页立刻换语言）。
  大厅支持玩法循环筛选与“只看可报名”，可清除筛选；筛选后仍无结果会给专门的空态提示。
  具备管理资格的人在大厅里额外有“我的场地”入口，管理界面仍是 `/regions gui`。
- `/regions create` 无参数进入创建向导：选玩法 → 选地块（自动生成场地 ID）→ 设置本玩法必填项
  → 检查并发布。阶段 3 只画当前玩法需要的格子（返回／观战点、出生点、装备预设、人数、时限），
  顶部显示“必填项 N/M”；中途关闭 GUI 后再次执行 `/regions create` 会回到离开时的阶段。
- `/regions game <id> join`：报名本场（权限 `regions.game.join`，默认随 `regions.use`）。
  工会战在报名时选择 Nation；没有 Nation 会被拒绝并说明原因。
- `/regions game <id> ready|unready`：准备与取消准备，权限 `regions.game.ready`。
- `/regions game <id> leave`：退出报名，或在进行中的比赛里弃权。
  报名页里这是一个**常驻按钮**（只要已报名就在）——主按钮在准备后会变成“取消准备”，
  退赛不该因此变成只能敲命令。退出通道不设额外权限，
  失去参与权限的玩家同样能退出并拿回装备。
- `/regions game <id> spectate`：到配置的场外观战点观看比赛（权限 `regions.game.spectate`）。
- `/regions game <id> status|result`：查看本场状态与上一场结果（权限 `regions.game.view`）。
- `/regions language <zh_CN|en_US|auto>`：选择自己的显示语言，权限 `regions.language.select`。
  **仅在 `config.yml` 的 `locale-mode: player` 时生效**；默认的 `locale-mode: server` 下全服统一使用 `language` 指定的语言。
  玩家未选择时按客户端语言归一（zh* → zh_CN、en* → en_US，其余回退服务器语言）。

## 八种玩法共用的参与契约

除 `free_event`（它没有比赛）之外的**七种玩法**遵守同一份契约，不分战斗、竞速还是捉迷藏：

| 契约 | 含义 |
|---|---|
| 走入场地 ≠ 报名 | 进场只提示一次"这里可以报名"，带冷却（`modes.entry-prompt-cooldown-seconds`，默认 60 秒）；只有 `join`（命令或大厅按钮）才写进名单。 |
| 完整报名册 | `join` / `ready` / `unready` / `leave` / `spectate` 对七种玩法一致可用；大厅主按钮按当前状态自动切换。 |
| 装备托管 | `replace-gear` / `kit` / `armor` / `offhand` 在**每一种**接管装备的玩法里行为一致，先持久化 escrow 再换装，写回确认后才删记录；托管期间禁止丢弃与拾取。 |
| 成员隔离 | 比赛期间局外人与选手互不造成玩家来源的伤害与负面状态；观战者既不造成也不承受。生物与环境伤害不受影响。 |
| 指令封锁 | 比赛进行中按 `modes.allowed-commands` 拦截逃跑类指令，七种玩法一视同仁。 |
| 结构化结果 | 每局结束写一条结果（outcome、胜者、原因、名次），`/regions game <id> result` 与大厅结果卡读同一份，重启后仍在。 |
| 崩溃可恢复 | 重启／重载中止未收尾的比赛并逐人归还装备；离线玩家保留待恢复记录，登录后继续。 |

同一玩家同时只能关联一场比赛（包括观战）；报名或观战阶段可退出后换场，运行中的历史参赛记录保留到本局结束。
竞速与捉迷藏准备阶段最多等待 10 秒，收齐回执后才开始；战斗与竞速的开赛传送失败会中止比赛。
装备托管期间还禁止外部容器操作、方块放置和向盔甲架／物品展示框转移物品，仍可使用自身背包及 Regions 菜单。
死亡时保留托管，重生后归还；玩家数据保存失败时保留恢复记录，不能删除 escrow 文件绕过。

`free_event` 是**唯一**没有比赛的玩法：它只是一块带规则、效果与触发动作的场地，
没有报名、胜负或装备托管，`join` / `ready` / `start` 会明确告诉你这不是对战类玩法，
而不是默默什么都不做。它接受的参数也只有触发动作能引用的返回点。

## 战斗玩法

三种战斗玩法共用 `org.cubexmc.regions.match` 的比赛状态机与同一份报名、准备、恢复、伤害隔离逻辑：

| 玩法 | 阵容 | 赛制与限时 | 胜负判定 |
|---|---|---|---|
| `dual_pvp` 双人决斗 | 恰好 2 人，由服务层强制（第三人报名会被拒绝，不只是模板容量） | 默认 BO1，可设 `best-of: 3`；每回合 180 秒（`round-seconds`），回合间休整 5 秒 | 对手死亡或有效弃权而自己仍存活；双方同死或回合超时为回合平局；BO3 最多 5 个实际回合，仍未两胜则整场平局 |
| `union_war` 工会战 | 两个 Nation，双方必须满额且人数相等，每队 2–10 人（默认 5） | 单局 600 秒（`timeout-seconds`） | 一方全员淘汰／弃权且另一方仍有存活者则该 Nation 获胜；双方同归于尽或超时为平局；同队永不友伤 |
| `free_for_all` 大乱斗 | 4–16 人（最低可设 2），每人独立，无队伍 | 单局 600 秒（`timeout-seconds`） | 最后存活者获胜；按淘汰顺序给出排名；同批淘汰为并列，超时仍多人生存为并列存活，不伪造唯一冠军 |

- **走入场地不等于同意参赛**：进入战斗场地只提示一次“这里可以报名”，提示带冷却
  （`modes.entry-prompt-cooldown-seconds`，默认 60 秒）；只有 `join`（命令或大厅按钮）才会进入名单。
- 工会战开报名前，场地主先用 `/regions game <id> teams`（先不带参数看编号）锁定本场两个 Nation
  （权限 `regions.game.start` 加场地管理资格）。同时属于多个 Nation 的玩家必须在报名时显式选队；
  比赛不会退化成自由混战。
- 开赛前会一次性检查资金映射、装备托管与传送：任何一名玩家失败都会撤销整场开赛，
  不会出现部分玩家先打的情况。
- 进行中比赛主动离开有 5 秒宽限，超过即计弃权；主动退出或被踢立即计弃权。
- 成员变化后先等 200 毫秒把同一批事件收齐再判胜负，因此范围伤害同归于尽会判平局，
  而不是判给后死的一方；准备阶段有超时上限，实体任务没回来就撤销开赛并点名未回执的玩家。
- 装备托管期间禁止丢弃与拾取（避免恢复快照与场上物品分叉）；只要还有上一局的待恢复记录，
  就不能报名新的一局。结算期间会告知还有几人待恢复以及资金状态。
- 观战者既不能造成也不能承受比赛伤害；候场者、局外人与其他比赛的玩家同样被隔离。
- 结果记录 outcome、获胜方、原因与奖励状态：`/regions game <id> result` 与大厅结果卡展示同一份结果。

## 竞速玩法

| 玩法 | 载具要求 | 计时与胜负 |
|---|---|---|
| `run_race` 跑步赛道 | 默认必须**步行**（`vehicle: none`） | 起点→检查点→终点；先到者名次靠前 |
| `boat_race` 划船赛道 | 默认必须在**船上**（含竹筏） | 同上；不在船上时检查点与终点不计 |
| `horse_race` 骑马赛道 | 默认必须**骑马**（含骷髅马、僵尸马） | 同上 |

- 载具约束可按阶段与逐个检查点覆盖：`vehicle` → `start-vehicle` / `finish-vehicle` →
  `checkpoint-vehicles`（分号分隔，一个检查点一项）。
- 时限三个历史键（`timeout-seconds` / `max-duration-seconds` / `duration-seconds`）等价，
  `0` 表示不限时；开赛方式 `start-mode: vote`（全员准备）或 `judge`（裁判 `/regions game <id> start`）。
- 名次即完赛顺序；没完赛的人按"坚持得更久的排前面"接在后面。
  **无人完赛就是平局**，不会为了凑一个冠军把某个人抬上去。
- 中途退赛与死亡的选手仍然留在这一局的结果记录里——"谁参加过这局"要查得到。

## 捉迷藏

- `seekers` 直接指定搜寻者人数，或用 `seeker-ratio`（0.05–0.8）按比例取；两者都不填按 0.2。
- `hide-seconds` 内搜寻者不能移动；`round-seconds` 到点仍有躲藏者存活则躲藏者获胜。
- 搜寻者攻击躲藏者 = 抓到，**伤害会被取消**；`found-becomes-seeker` 决定被抓的人转阵营还是出局。
- 除这一下之外，本局内任何玩家来源的伤害都被拒绝——此前躲藏者可以反过来把搜寻者打死。
- `seeker-kit` / `hider-kit` 分角色发装备，未设时回落到 `kit`；`armor` / `offhand` 同样生效。

## 权限

以 `plugin.yml` 为准。参与类叶节点挂在 `regions.use` 之下，声明与命令里的实际检查一致
（声明了却不检查的节点会让服主按错误的前提配权限）：

| 权限 | 默认 | 说明 |
|---|---|---|
| `regions.use` | true | 参与场地的活动（`/regions game ... join\|ready\|spectate\|status`）。撤销它可挡住一组玩家参加他人活动；办赛另有来源所有权门槛，不靠这个节点。 |
| `regions.game.view` | true | 查看比赛状态与结果（`/regions game <id> status\|result`）。 |
| `regions.game.join` | true | 报名（`/regions game <id> join`）。**七种玩法一致**：进入区域本身不等于报名，只有该节点加命令才会把你放进名单。 |
| `regions.game.ready` | true | 准备与取消准备（`/regions game <id> ready\|unready`）。 |
| `regions.game.spectate` | true | 到观战点观看进行中的比赛（`/regions game <id> spectate`），竞速与捉迷藏同样支持。 |
| `regions.language.select` | true | 选择个人显示语言，仅 `locale-mode: player` 时生效。 |
| `regions.admin` | op | RuleGems 统治者管理入口，仍需来源所有权；下含 `regions.game.start` / `regions.game.end`。 |
| `regions.game.start` | false | 裁判发令（竞速／捉迷藏的 `start-mode: judge`）与锁定国家对阵（`/regions game <id> teams`），另需场地管理资格。战斗三兄弟在全员准备后自动开赛，没有发令这一步。 |
| `regions.game.end` | false | 强制结束比赛，另需场地管理资格；战斗场地还要求 `regions.region.edit`。 |
| `regions.superadmin` | op | 紧急接管与事故恢复。 |

退出比赛（`/regions game <id> leave`）不设额外权限：失去参与权限的玩家必须仍能退出并拿回装备。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `modes.entry-prompt-cooldown-seconds` | 60 | 走入比赛场地时“这里可以报名”提示的冷却秒数，七种玩法共用；缺失时按 60 处理。 |

其余配置见 `config.yml` 内的注释；语言策略 `locale-mode`、审计上限与发布保留数等沿用既有说明。

## “现在能不能开一场”只有一份答案

活动大厅的灰卡原因、创建向导的必填项、场地详情页的就绪摘要，现在全部出自
`VenueReadiness`。阻塞原因分五层，展示顺序即优先级：

| 层 | 含义 | 谁会被它拦住 |
|---|---|---|
| CONFIG | 玩法必填项没填（出生点、出场点、装备预设…） | 创建向导 |
| VALIDATION | 发布校验的 ERROR 条目 | 发布页、大厅、详情页 |
| DEPENDENCY | 来源插件 / 工会来源不可用 | 大厅、详情页 |
| LIFECYCLE | 未发布、停用、冻结、归档 | 大厅、详情页 |
| MATCH | 上一局正在恢复、这一局已开打 | 大厅、详情页 |
| ROSTER | 人数已满 | 大厅、详情页 |

**各页只按自己关心的层拦**：向导看 CONFIG，大厅看运行时那几层——
向导意义上的“必填项”不该把已发布的场地在大厅里变灰（那是发布校验的职责）。
原因文案统一在 `readiness.*`，三处显示的是同一句话。

## 玩法页的三块标签

玩法页拆成**基础参数 / 点位 / 赛制**三块，左上角三个按钮切换，当前那块是绿色；
某块在该玩法下没有内容就不显示，不会点进去一片空白。

- **基础**：人数上下限、是否需要确认、是否托管装备、装备预设、载具检查
- **点位**：出生点清单（战斗玩法）、复活点、起终点与检查点（竞速）
- **赛制**：时限、回合数、开赛方式、裁判、工会战的对阵与外交前置

出生点不再是一个"已设 N 个"的计数按钮：点进清单页可以逐个看坐标、逐个删，
站到位置上点"在这里添加"即可；工会战的甲/乙两组在同一页切换。

## 药水与范围效果的隔离

伤害类药水走伤害事件，和近战、箭矢同一套判定；**纯效果药水**（中毒、缓慢、虚弱、失明）
不触发伤害事件，所以另接了泼洒药水与滞留药水云两个事件：

- 局外人不能给选手上状态，选手也不能影响观战者与局外人（双向）
- 候场、准备、回合间隔、恢复阶段一律不生效
- 同局敌对双方可以互上负面效果；**工会战同队的负面效果被友伤规则拦下**，治疗、增益类则放行
- 自己喝的药永远算数；发射器丢的药因为无法归因，对选手一律无效
- 混合药水只要含一个负面效果就整瓶当负面处理（否则搭一个回血就能把中毒送进场里）

## 比赛期间的指令封锁

比赛一旦过了开赛屏障（PREPARING → 回合间隔），选手只能用本插件的 `/regions`；
`/spawn`、`/home`、`/tp`、`/l edit` 这类指令一律拦下——在 PVP 里它们就是最短的逃跑通道。
服主可用 `modes.allowed-commands` 额外放行（写根指令，不带 `/`）；`plugin:cmd` 形式按去掉命名空间后的名字判定，
所以 `/lands:l` 与 `/l` 同等对待。

这道封锁独立于场地的 `commands` Flag，**不受 `regions.bypass.flags` 影响**（比赛内部保护不认场地级 bypass）；
只有 `regions.superadmin` 能绕过，留给紧急处置卡住的局。

## 状态安全

临时效果使用持久化 lease（`effect-escrow.yml`），战斗和回合装备分别使用装备托管文件。正常退出、死亡、reload、停服和下次启动/登录都会尝试恢复。Folia 停服阶段不会提交无法保证执行的实体任务，而是保留托管数据供下次安全恢复。

Contract 奖励操作另存于 `reward-funding.yml`。一局比赛的 lock、自然胜者 settle、强制结束/reload/崩溃恢复 refund 共用同一个 transaction operation id，只有持有该锁的交易才能终结资金。若锁定回执丢失，Regions 保留 `PREPARING` lease；撤销开赛或重启时先用该 ID 退款，Contract 明确报告无锁时才以同一 ID 重放锁定并退款。仍不能确认时 lease 留存，同场地暂不能开始新的资金局；先恢复 Contract/Vault 后 reload，持续失败则核对两侧 operation 与合同状态并人工复核。无法确认的部分结算也保留 lease，不会生成第二次付款。删除该文件会破坏恢复链，禁止把它当作清理手段。

比赛元数据另存于 `matches.yml`（schema `match-store-version: 1`），只保存阶段、名单、队伍／Nation 快照、结果与恢复进度，不复制物品和余额：装备内容只在装备托管文件，资金只在 `reward-funding.yml`。三者是相互独立的真相来源，任何一个文件都不保存另一份的可变副本。

装备恢复先读托管记录，在玩家所属线程写回完整快照并确认，然后落盘“已确认”标记，最后才删除该记录。
待恢复期间玩家不能报名新的一局，也不会被新的托管覆盖旧快照。重启时若恢复已经确认，只补做记录清理，不会覆盖玩家之后获得的新背包；尚未确认的恢复保留待恢复，离线玩家在登录后继续。重启或 reload 会中止未收尾的比赛，不续打半局，资金经既有 lease 退款。

战斗玩法的限时按各自规则：决斗每回合 180 秒（`round-seconds`），工会战与大乱斗 600 秒（`timeout-seconds`）；赛跑／回合模式沿用各自的 `timeout-seconds`。所有延迟任务都绑定具体局实例；旧局计时器不能结束或修改新局。上一局恢复完成前，同一区域不会启动下一局。



## 已知边界

- 尚未发布首个正式版本；当前数据格式将作为首个 release 基线，内部开发期的旧格式不在兼容范围内。
- 未知或未实现的 Capability / Condition 一律**校验失败**而不是默认放行——这是有意的安全默认。
- 校验与发布诊断已改成稳定错误码 + 双语 `errors.*`；尚未键化的还有 `ServiceResult.reason`、
  资金类 `FundingResult.detail` 与审计行里的部分英文诊断文本。
- 聊天、标题、比赛广播与 **GUI 菜单文案**都已按接收者语言渲染（`locale-mode: player` 时中文玩家与
  英文玩家各看各的）；例外是控制台输出与 `ChatInputState` 的取消关键词（后者是输入匹配，不是展示文本）。
- race / hide-and-seek / 赞助 / 多人分成的**真实资金结算**尚未实现（见 `PLAN.md` §5.2 阶段 D）。
- **战斗玩法的实服／真人验收尚未执行**：目前只有自动化验证（`:Regions:test` 192 例、`:Regions:build` 与
  `:Regions:jarGate` 通过）。真实 Lands API 行为、Purpur／真实 Folia 运行、旧数据升级演练与真人多人流程
  仍属待办，见 `PLAN.md` §10.2 与 [`REAL_PLAYER_TEST.md`](REAL_PLAYER_TEST.md)。文档不会把未做的验收写成已通过。

## 相关文档

- 本轮实施计划：[`PLAN.md`](PLAN.md)（国际化、流程简化、Nation 工会战、PVP 与单命大乱斗；
  M3–M7 的代码与自动化验证已完成，§10.2 的实服／真人验收尚未执行）
- 总体路线与历史：仓库根 [`PLAN.md`](../PLAN.md)
- [架构](docs/architecture.md)
- [兼容性](docs/compatibility.md)
- [自动回归基线](docs/regression-baseline.md)
- [发布检查单](docs/release-checklist.md)
- [真人验收清单](REAL_PLAYER_TEST.md)
