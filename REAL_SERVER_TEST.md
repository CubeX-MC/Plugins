# 实服验证清单

> 本轮（2026-08-19）重构触及 **8 个插件**的运行时行为，另有外置模式这条**从未在实服跑过**的新路径。
> 这份清单只列**必须上服务器才能验的**项；能在构建期验的都已由 `gradlew build jarGateAll` 覆盖。
>
> 计划与待办看 [`PLAN.md`](PLAN.md)；这里只写"怎么测、期望看到什么、炸了看哪里"。

## 0. 环境准备

| 项 | 要求 | 原因 |
|---|---|---|
| 服务端 | **Paper 1.21.11+** | Contract / Regions 编译到 paper-api 1.21.11 |
| Java | **21** | Clarity 编译到 Java 21（全仓唯一例外） |
| 前置 | **Vault** + 任一经济提供方 | Contract / EcoBalancer / StateCharge 都是 `depend: [Vault]`，缺了会 `abortEnable` |
| 可选前置 | **CMI**（或任一监听 legacy 聊天事件的插件） | 阶段 2 的聊天链路对照组要用 |
| 测试账户 | 一个**服务器银行账户**（如 `cubex_bank`），先用经济插件建好（`/eco set cubex_bank 0`） | §3.2 的 StateCharge `economy.account` 一组要核对钱有没有真的转进去 |

出 jar：

```bash
./gradlew shadowJarAll
```

产物在各 `<插件>/build/libs/*.jar`（**跳过 `*-plain.jar`**，那是不含依赖的空壳）。

---

## 1. 阶段一 — CubeXLib 外置模式 ✅ **已通过（2026-08-20，Paper 26.1.2）**

这是整轮唯一**没有任何实服证据**的机制：外置模式插件的类能否在 Paper 的 `PluginClassLoader`
下解析到 CubeXLib 提供的 `cubex-*` 与 Kotlin stdlib。

**装**：`CubeXLib.jar` + `CookbookHelloExternal.jar`（两个都要）

- [x] **1.1 启动**：控制台出现 `CubeXLib 0.1.0 就绪：cubex-* 共享模块以原包名提供给外置模式插件`，
      且 `CookbookHelloExternal` 正常启用。
      **实测**：`[CubeXLib] CubeXLib 0.1.0 就绪：cubex-* 共享模块以原包名提供给外置模式插件`，
      `CookbookHelloExternal` 随后正常启用 —— **跨插件类可见性成立**，外置模式可用。
- [x] **1.2 加载顺序**：CubeXLib 的 `plugin.yml` 写了 `load: STARTUP`，
      确认它在 `CookbookHelloExternal` **之前**加载（看控制台顺序）。
- [x] **1.3 功能**：`/hello` 与 `/hello Steve` 返回带颜色的问候语；改 `plugins/CookbookHelloExternal/config.yml`
      的 `greeting` 后重启生效。
- [x] **1.4 缺失前置**：**只装** `CookbookHelloExternal`、不装 CubeXLib →
      Paper 如期在**加载期**拒绝：
      `UnknownDependencyException: Unknown/missing dependency plugins: [CubeXLib]`，
      不会启用后随机崩 —— 构建注入的 `depend` 确实生效了。
- [x] **1.5 内嵌模式不受影响**：装 `CookbookWelcomeBack.jar`（内嵌）**且不装 CubeXLib** → 应当正常工作。
      这条证明内嵌插件对 CubeXLib 确实是零依赖。

---

## 2. 阶段二 — 本轮改动的回归

### 2.0 执行顺序（插件要分三轮换装，乱插会白跑）

**Metro 与 Railway 本就不支持同时安装**（同包名同主类，AGENTS.md 已定），所以必须分轮。

| 轮次 | 装什么 | 目的 |
|---|---|---|
| **A** | Vault + 经济插件 + **CMI**（或任一 legacy 聊天监听者）+ Contract · Regions · EcoBalancer · **Metro** · MountLicense · RuleGems · Clarity | 全服走 **legacy** 链路，跑 2.1 全部 + 2.2 ~ 2.5 |
| **B** | 只留 Vault + 经济 + **Contract · Regions**（拿掉 CMI / EcoBalancer / Metro） | 全服走**现代**链路，跑 2.1.7 —— 这是唯一能验证 `AsyncChatEvent` 分支的场景 |
| **C** | 把 A 里的 Metro 换成 **Railway** | 同源代码，快速复跑 2.1.1 ~ 2.1.5 与 2.5 |

#### 2.0.1 开服先看日志（**A 轮第一件事，2 分钟**）

服务端是 **Paper 26.1.2**，比插件编译目标（paper-api 1.21.11 / spigot-api 1.18.2）新很多。
Paper 已把 legacy `AsyncPlayerChatEvent` 标记废弃多年，**若这个版本已经移除了它**，
所有注册 legacy 监听器的插件会在 `registerEvents` 反射扫描方法时直接抛
`NoClassDefFoundError` / `ClassNotFoundException`。

- [ ] **2.0.1** A 轮启动后通读日志，确认 Contract / Regions / EcoBalancer / Metro
      **都没有**监听器注册相关的异常。
      - **正常** → 按下面继续。
      - **报错** → 立刻停下来告诉我：说明这个 Paper 版本已经删掉了 legacy 事件。
        好消息是 `ModernChatBridge` 已经把现代事件接上了，修法是**去掉 legacy 监听器**
        而不是重做设计；但那会**放弃纯 Spigot 支持**，属兼容性决策，要你拍板。

### 2.1 聊天输入（改动最大，5 个插件）

**各插件的触发点**（照着点最快）：

| 插件 | 怎么触发聊天提问 |
|---|---|
| Contract | 合同创建 GUI → 输入金额 / 描述 / 模板名（描述那一步还支持 `clear`，顺带验 2.1.2 的 clear 分支） |
| Regions | `RegionCreationMenu` 创建向导 → 输入 region id、显示名、Mode 参数 |
| EcoBalancer | 税收 GUI → 新建策略（输入名称）、编辑检查时间、Max Deduction、测试余额 |
| Metro / Railway | 线路设置 → 改名 / 改速度 / 改票价 / 克隆线路 |


状态机下沉到 `cubex-gui`，并给 Contract 补了原本缺失的 `AsyncChatEvent` 监听、
给 EcoBalancer / Metro / Railway 经 `ModernChatBridge` 反射补了现代事件监听。

对每个插件（**Contract · Regions · EcoBalancer · Metro · Railway**）各做一遍：

- [ ] **2.1.1 正常输入**：打开 GUI → 触发需要聊天输入的操作 → 在聊天栏输入内容 →
      提示词收到输入，且**这一行没有出现在公屏上**。
- [ ] **2.1.2 取消**：输入 `cancel`（Metro/Railway 另试 `取消`；EcoBalancer 试
      `messages.gui.chat_cancel_keyword` 配的词；Regions 试 `gui.prompt.cancel-word`）→ 走取消分支。
- [ ] **2.1.3 超时**（**仅 Contract**，其余四家本来就没有超时）：触发提问后不输入，
      等到超时 → 收到超时提示，之后再发言应当正常进公屏。
- [ ] **2.1.4 退出清理**：触发提问 → 直接退出游戏 → 重新进入 → 发言应当正常进公屏（不被吞）。
- [ ] **2.1.5 不重复处理**：正常输入一次，确认回调**只跑了一次**
      （例如改名只改一次、不出现两条成功提示）。这条验的是两条聊天链路的去重。

**链路对照组**（本轮的核心风险，务必做）：

- [ ] **2.1.6 有 legacy 监听者**：装上 CMI（或任一监听 legacy 的插件），重复 2.1.1 →
      全部仍然正常。
- [ ] **2.1.7 无 legacy 监听者**：**只装 Regions 或 Contract**（这两家两个事件都监听），
      不装 CMI / EcoBalancer / Metro / Railway → 此时 Paper 走**现代**链路，
      重复 2.1.1 → 仍然正常。
      这条是唯一能验证 `AsyncChatEvent` 分支真的生效的场景。
- [ ] **2.1.8 反射桥可用性**：EcoBalancer / Metro / Railway 在 Paper 上启动时不应有反射相关报错；
      若怀疑没接上，可临时在代码里打印 `ModernChatBridge.isAvailable`（Paper 上应为 `true`）。

### 2.2 PDC / UUID —— MountLicense

- [ ] **2.2.1** 注册载具、绑定钥匙、召回、定位、停车/锁定各走一遍，行为与改动前一致。
- [x] **2.2.2 损坏数据不崩** ✅ **已验**（`not-a-uuid` 的钥匙被当作未绑定，安静忽略，无异常刷屏）：用 NBT 编辑器把某个钥匙物品的
      `owner` / `keyBoundVehicle` 值改成 `not-a-uuid` → 相关命令应当**当作"没有绑定"处理**，
      而不是抛异常刷屏。

### 2.3 冷却 —— RuleGems

- [ ] **2.3.1** 触发 `/navigate` 类功能两次 → 第二次提示剩余秒数，**数字与改动前一致**
      （下沉时按下沉前的算法逐值对齐过，单测锁着，但实服再看一眼）。
- [ ] **2.3.2 冷却中不续期**：冷却期间反复触发 → 剩余秒数应当持续**递减**，不会被重置。
- [ ] **2.3.3 reload 生效**：改配置里的冷却秒数 → `reload` → 立刻按新值生效，不需要重启。
- [ ] **2.3.4 情报广播**：`GemIntelBroadcaster` 的按玩家冷却仍然生效（同一玩家不会被短时间重复推送）。

### 2.4 物品槽位遍历 —— Clarity

- [ ] **2.4.1** `/clarity item scan <玩家> all` 的输出里，槽位标签格式**与改动前逐字一致**：
      `hand` / `equipment[helmet]` / `inventory[12]` / `ender[3]`。
- [ ] **2.4.2** `sweep` / `purge` 确实把清理后的物品**写回了原来的格子**
      （尤其检查副手与四件盔甲——它们各有各的写回 API）。
- [ ] **2.4.3** 末影箱里的物品也被扫到并能写回。

### 2.5 GUI 铺底 —— Metro / Railway / EcoBalancer

- [ ] **2.5.1** 主菜单/各界面的**灰色（EcoBalancer 是黑色）玻璃板铺底**外观与改动前一致，
      按钮没有被玻璃板覆盖。

---

### 2.6 A/B/C 三轮实测结果（2026-08-20，Paper 26.1.2）

**2.0.1 结论：legacy `AsyncPlayerChatEvent` 在 Paper 26.1.2 上仍然存在**，四个插件的监听器注册全部正常。
`ModernChatBridge` 因此暂时只是防御性的（等 Paper 真移除时才成为承重件）。

| 项 | 结果 |
|---|---|
| 2.1 聊天输入（A 轮 5 家） | ✅ 全过 |
| 2.5 GUI 铺底 | ✅ 正常 |
| B 轮（现代链路） | ✅ **Contract 的提问已验过，无问题** —— `AsyncChatEvent` 分支确实生效。Regions 因 Lands 缺席而冻结，那是 §5.2 决策 9 的预期行为，不是 bug |
| C 轮（Railway） | ✅ 聊天输入无问题 |

**发现的问题**：三个都是**既有 bug**（非本轮重构引入），实服跑一遍才暴露出来 ——
这正是这份清单存在的意义。

- [x] **RuleGems `/rg reload` 崩溃** —— **真 bug，已修**。
      `CommandMaps.unregister` 原本边遍历边 `iterator.remove()`，而 Paper 26.x 交回来的
      `knownCommands` 其 entry-set 迭代器**不支持 remove** → `UnsupportedOperationException`
      把整条命令带崩。已改为先收集标签、再逐个 `remove` 且各自 guard，
      失败时降级到 `Command.unregister` 并 WARNING；**只报告真正删掉的标签**，
      免得调用方误以为清理成功了。4 条单测锁住（含 unmodifiable map 这一路）。
- [x] **EcoBalancer GUI 标签显示原始颜色码** —— **既有 bug，已修**（Metro 正常，
      所以不是平台行为变更）。`GuiManager` 策略列表那一行：
      `if (isActive) tr(...) else "&e"` —— **`else` 分支是裸字面量，从来没过 `tr()`**，
      所以非激活的策略条目会把 `&e` 原样显示出来。已改为同样走 `tr()`
      （新键 `messages.gui.tp_item_inactive_prefix`，fallback 仍是 `&e`）。
- [ ] **EcoBalancer `Could not rename the log file`** —— 它自己的文件 logger，与本轮无关。
- [x] **MountLicense 钥匙朝天右键不召唤** —— **既有 bug，已修**（`/ml recall` 命令能召回，
      证明 PDC 读取与召回逻辑都是好的，问题在事件监听层）。
      `KeyItemListener.onAirInteract` 带了 `ignoreCancelled = true`，而
      **`PlayerInteractEvent.isCancelled()` 返回的是 `useInteractedBlock() == DENY`**——
      空右键根本没有方块可交互、该值默认就是 DENY，事件因此恒为"已取消"，
      带 `ignoreCancelled` 的处理器**一次都不会触发**。已去掉该参数；
      handler 里的 action 判断与 `isKey` 收窄本来就够，不需要靠它过滤。
- [x] **Clarity `unknown item scope 'inventory[12]'`** —— **不是 bug，是用法**：
      `inventory[12]` 是**输出里的槽位标签**，不是命令参数。scope 只接受
      `hand` / `inventory` / `equipment` / `ender` / `all`。
- [x] **Clarity item scan 扫不到临时造的物品** —— **也不是 bug**：
      `items.attributes.remove-modifier-ids` 与 `max-amounts` **默认都是空列表**，
      没配规则自然"nothing matched configured rules"；而 `item purge` 只认 `leveltools` 一条规则。
      正确的造样本方式见 §2.7。
      **Clarity 玩家侧（attribute + 药水效果）已实测正常**，能正确标出 `dur=INFINITE` 的效果。

### 2.7 Clarity 的正确测试样本

**A. LevelTools 残留**（`item scan` / `item purge leveltools` 唯一能命中的一类）：

```
give @s diamond_pickaxe[minecraft:custom_data={PublicBukkitValues:{"leveltools:leveltoolslevel":5,"leveltools:leveltoolsxp":1200}}] 1
```

- [ ] `/clarity item scan <你> hand` → 应命中
- [ ] `/clarity item purge <你> hand leveltools` → 清掉 PDC 键（`remove-lore: true` 时连带清 lore 行）

**B. 道具 attribute 规则**（**必须先配规则，否则永远扫不到**）：
在 `plugins/Clarity/config.yml` 的 `items.attributes.remove-modifier-ids` 加一条（例如 `"adapt:leftover"`），
`/clarity reload` 后再造带该 modifier 的物品。

**C. 玩家侧无限效果**（Clarity 的立身之本，截图里已经扫出来了）：

- [ ] `/clarity player purge <你> effect all-infinite` → 清掉 `dur=INFINITE` 的 regeneration / health_boost
- [ ] `/clarity player purge <你> attr <namespace:id>` → 清单个属性

> 语法是 `attr <namespace:id>` 或 `effect <type|all-infinite>`，**没有 `purge ... all` 这种形式**。

## 3. 阶段三 — PLAN 里原有的首发验证

以下不是本轮引入的，是 [`PLAN.md`](PLAN.md) 早就挂着的项，可以和上面一起跑。

### 3.1 §4 R1 — Regions ↔ Contract 托管故障注入（**全仓价值最高的一条**）

真钱余额链路仍待真人验证；以下不涉及玩家余额的连接与持久化前置已经完成：

- [x] Paper 1.21.11 + Vault + EssentialsX + Contract + Regions 联合启动；Regions 经提供方
      ClassLoader 调到真实 Contract 服务，不存在的 WAGER 正确返回 `CONTRACT_NOT_FOUND`。
- [x] 无 Contract 启动后注入 `PREPARING` lease；reload 返回 `PROVIDER_UNAVAILABLE`，正常停服后
      `reward-funding.yml` 仍保留相同 state 与 operation id。
- [x] 双侧自动化测试覆盖落盘重启后的同 operation 重放、已完成终态不重复执行，以及
      `REVIEW_REQUIRED` 保留 `SETTLING` 且不回退成 refund。

本地可用 `./gradlew :Regions:runServer` 启动联合服；加
`-PregionsRunWithContract=false` 会改用隔离的 `Regions/run-no-contract` 数据目录并省略 Contract。
**每一条都要在结束后核对：`余额 + 托管 = 之前余额`。**

- [ ] settle 执行到一半强制关服 → 重启后以**同一 operation id** 重放，不得二次付款。
- [ ] Vault provider 中途卸载 → Regions 侧应进入 `REVIEW_REQUIRED`，不得静默吞钱。
- [ ] Contract 先于 Regions 卸载 → Regions 的 lease 应保留，重启后可重放。
- [ ] 同一 operation id 重复提交 → 幂等，只生效一次。
- [ ] 走一遍 `REVIEW_REQUIRED` 之后的人工处理路径。

### 3.2 各插件首发验证

- [ ] **Contract**：资金核心、WAGER、PARTNERSHIP、GUI 大厅、Reputations 桥、PAPI 占位符
- [ ] **StateCharge**（计费模型 2026-08-20 重做，下面这组是新的）：
      - [ ] `/sc` 打开交易页：**只显示你有权限的状态**；开着的按钮发光
      - [ ] 点击开启 `small` → 体型立刻变化，聊天提示"关闭前会持续计费"
      - [ ] 等一个结算周期（默认 60s）→ 余额减少；金额 ≈ 费率 × 已开启秒数（**不取整**）
      - [ ] 点击关闭 → 体型还原，提示报的是**本次开启到关闭的累计总额**
            （2026-08-21 修正：此前报的是最后一个周期的零头。开满 3 个周期再关，
            提示金额应 ≈ 3 个周期之和，而不是 1 个周期的钱）
      - [ ] **离线不计费**：开着状态下线，等几分钟再上线，余额不应减少
      - [ ] 互斥：开着 `small` 时点 `giant` → `small` 自动关闭并结算，不叠加
      - [ ] 余额保险：`/sc guard 500`（或交易页的盾牌按钮 → 聊天输入）→ 余额跌破 500 时
            全部收费状态自动关闭并收到提示
      - [ ] 保险已触发时再点开启 → 直接拒绝，而不是开一秒又被关掉
      - [ ] 扣款失败（把余额清零）→ 状态被强制关闭，控制台记 WARNING
      - [ ] 存档：手动改坏 `plugins/StateCharge/states.yml` 一个字符 → 重启回退 `.bak`，不丢档
      - [ ] 旧存档：放一份 v1 格式（`players.<uuid>.<state>: 1800`）→ 启动时明确告警并忽略，不误换算
- [ ] **StateCharge 累计总额**（2026-08-21 新增，`states.yml` 的 `session-charged` 段）：
      - [ ] 跨重启：开着状态关服 → 重启 → 再关掉，提示金额应从**最初开启**算起，不从重启算起
      - [ ] 扣款失败被强制关闭后重新开启 → 累计从 0 重算（不该继承上一段）
      - [ ] 互斥自动关闭（开 `small` 时点 `giant`）→ `small` 报的也是它自己那一段的累计总额
      - [ ] 升级路径：拿一份**没有** `session-charged` 段的旧 `states.yml` 启动 → 正常加载，
            升级前就开着的状态从 0 重新累计（这是预期，不是 bug）
- [ ] **StateCharge 内循环经济 `economy.account`**（2026-08-21 新增）：
      - [ ] **升级路径**：拿一份 `config-version: 1` 的旧 config 启动 → 自动补出
            `economy.account: ''`、版本变 2，且**行为与升级前完全一致**（钱照旧销毁）
      - [ ] 留空（默认）→ 扣款后没有任何账户余额增加
      - [ ] `uuid:<cubex_bank 的 uuid>` → 玩家扣多少，该账户就涨多少（逐笔核对，别只看总数）
      - [ ] `name:cubex_bank` → **从不登录的账户也能收到钱**；启动日志确认经济插件认得这个名字
      - [ ] 裸名字 `cubex_bank` → 启动日志打出解析到的 UUID 与
            `Pin it with 'economy.account: uuid:...'` 提示；核对 UUID 是不是你要的那个账户
      - [ ] 写一个**服务器从没见过的名字** → 启动记 SEVERE，插件**照常启动**、状态照常开关、
            照常扣钱，但每笔扣款一条 WARNING（钱确实丢了 —— 确认日志能让你对上账）
      - [ ] `bank:<名字>` 且经济插件不支持 bank → SEVERE 并降级，不静默吞钱
      - [ ] 改错的配置修好后 `/sc admin reload` → **不重启**即重新解析成功
      - [ ] 配置没变时连按两次 `/sc admin reload` → 不应重复出现账户解析日志（跳过重解析）
- [ ] **Regions 权限面**（`f670632` + `f87d915`，两批死节点接线）：
      - [ ] ⚠️ **上服前先扫权限插件配置**：`regions.use` 以前是死节点，现在真的生效。
            哪个组显式写了 `-regions.use`，那些人现在会被挡在 `/regions game` 之外
      - [ ] 普通玩家（有 `regions.use`）能 `/regions game <id> ready|status`
      - [ ] 撤掉 `regions.use` 的玩家 → 被拒；统治者与超管**不受影响**（`has()` 直通）
      - [ ] 只发 `regions.reload` 给一个非超管 → 他能 `/regions reload`，
            但 `/regions cleanup`、`/regions inspect` 仍被拒
      - [ ] 统治者（`regions.admin`）不带 `regions.reload` → **被拒**。
            全服级操作刻意不走统治者旁路，这条挂了说明 `canUseGlobalAdministration` 被改错了
      - [ ] 超管不带任何细粒度节点 → 四条命令（含 `doctor`）全部照常
      - [ ] `regions.template.apply` 已从 `plugin.yml` 删除 → 权限插件里留着这行也不影响建场地
- [ ] **Clarity**：属性清理主流程（另见 2.4）
- [ ] **Reputations**：Vault 模式共享信誉服务

---

## 4. 记录

跑完把结果写回 [`PLAN.md`](PLAN.md) 对应条目（勾选 + 日期），
**没覆盖到的路径也要写下来** —— PLAN §8 的纪律是"计划文件的勾选状态不能当作事实"。
