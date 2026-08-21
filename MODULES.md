# CubeX-Plugins · 共享模块开发与使用指南

> **面向读者**：CubeX 插件开发者、维护者与 AI 编码助手。  
> **参考实现**：[`Contract`](Contract) 是全仓接入 `modules/` 的**标准参考插件**。遇到模式不确定时，请直接参考 Contract 的源码实现。

---

## 1. 共享模块全景矩阵

| 模块名称 | 定位 | 接入插件数 | 接入插件列表 |
|---|---|:---:|---|
| [`cubex-core`](modules/cubex-core) | 必选基础：生命周期托管、Terminable 资源栈、日志、文本着色、补全 | **12/12** | 全部 12 个插件 |
| [`cubex-config`](modules/cubex-config) | 配置管理：YAML 读写、默认值合并、版本化迁移框架、ReloadChain | **10/12** | BookLite, FAWEReplacer, MountLicense, Contract, EcoBalancer, RuleGems, Metro, Railway, Regions, StateCharge |
| [`cubex-i18n`](modules/cubex-i18n) | 国际化服务：多语言 fallback 链、MiniMessage / Legacy 富文本 | **10/12** | 同上 10 个插件 |
| [`cubex-scheduler`](modules/cubex-scheduler) | 调度器抽象：Paper / Folia / Spigot 跨平台统一调度、生命期绑定 | **7/12** | Contract, EcoBalancer, RuleGems, Metro, Railway, Regions, StateCharge |
| [`cubex-integrations`](modules/cubex-integrations) | 跨插件连接：无状态 ClassLoader 反射服务连接器 | **2/12** | Contract, Regions |
| [`cubex-database`](modules/cubex-database) | SQLite 数据库：安全连接工厂、PRAGMA 参数配置、事务闭包 | **3/12** | BookLite, EcoBalancer, RuleGems |
| [`cubex-command`](modules/cubex-command) | 动态指令：CommandMap 解析、动态指令注册与生命期注销 | **2/12** | FAWEReplacer, RuleGems |
| [`cubex-gui`](modules/cubex-gui) | 界面交互：基于 Inventory 实例事件路由的 Menu 框架、ItemBuilder、Pagination、ChatInputState | **5/12** | Contract, Metro, Railway, EcoBalancer, Regions |
| [`cubex-spatial`](modules/cubex-spatial) | 空间索引：Point3D, Range3D (AABB), Octree 八叉树索引 | **2/12** | Metro, Railway |
| [`cubex-economy`](modules/cubex-economy) | Vault 经济封装 + `economy.account` 入账路由（内循环经济） | **1/12** | StateCharge |

---

## 2. 各模块详解与 API 使用指南

### 2.1 `cubex-core` (必选)
主类继承 `CubexPlugin`，托管生命周期并在插件卸载时以 **LIFO (后进先出)** 自动释放绑定的全部资源。

#### 核心 API 与模式
```kotlin
class MyPlugin : CubexPlugin() {
    override fun enablePlugin() {
        // 1. 日志与文本
        log().info("MyPlugin enabling...")
        val colored = text().color("&aHello &eWorld")

        // 2. 注册 Bukkit 监听器与指令
        registerListener(MyListener())
        registerCommand("mycmd", MyCommandExecutor())

        // 3. 绑定有生命周期的组件（disable 时自动按反序关闭）
        val myService = MyService().also { bind(it) } // MyService 实现 Terminable 或 AutoCloseable
        
        // 4. 非致命缺失依赖时安全自禁用
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            abortEnable("Vault is required but missing") // 记录 WARNING 并安全 disable
            return
        }
    }

    override fun disablePlugin() {
        // 自定义额外清理逻辑（绑定的资源在此之后自动 LIFO 关闭）
    }
}
```

#### 命令补全统一工具 (`CubexCommandSuggestions`)
用于抹平 Paper `BasicCommand`（空数组 `[]`）与 Bukkit（`[""]`）在根补全阶段的形态差异：
```kotlin
val suggestions = CubexCommandSuggestions.root(args, listOf("create", "reload", "list"))
```

#### 事件注册薄糖 (`onEvent`)
注册完**自动**绑进 `Terminable` 资源栈，插件 disable / reload 时自动注销：
```kotlin
override fun enablePlugin() {
    onEvent<PlayerJoinEvent> { event ->
        messager().send(event.player, text().color("&a欢迎回来"))
    }
    // 需要提前注销时接住返回值:val handle = onEvent<...> { ... }; handle.close()
}
```
它的价值不是少写几行，而是让"忘记注销监听器"这类**不会立刻报错**的 bug 不可能发生。
一个类里要处理多个事件、需要 `@EventHandler` 注解语义、或监听器本身有状态时，
照旧写 `Listener` + `registerListener`。

#### PDC 读写扩展 (`CubexPdc`)
`PersistentDataContainer` 的读写样板，重点是把"**外部数据不可信**"收敛到一处 ——
PDC 里的内容可以被手改、被别的插件写坏、被版本迁移留下半截：
```kotlin
// 布尔标记(底层 BYTE,按键是否存在判断)
if (item.itemMeta.persistentDataContainer.hasFlag(guiKey)) return
meta.persistentDataContainer.setFlag(guiKey)

// UUID:格式损坏返回 null,不抛
val owner: UUID? = entity.persistentDataContainer.getUuid(keys.ownerUuid())
entity.persistentDataContainer.setUuid(keys.ownerUuid(), player.uniqueId)

// 枚举:按 name 存取,**已删除的枚举项返回 null 而不是抛**
val state = mount.persistentDataContainer.getEnum<VehicleState>(keys.state())
mount.persistentDataContainer.setEnum(keys.state(), VehicleState.PARKED)

// 带默认值
val role = meta.persistentDataContainer.getStringOr(keys.itemRole(), "none")
```
> `getEnum` 不用 `valueOf`：枚举增删项是常见的版本演进，旧存档里留着已删除的名字**不该让插件炸掉**。

#### 按玩家冷却 (`Cooldown`)
```kotlin
// 时长是 supplier —— reload 改了配置立刻生效;返回 <= 0 表示不设冷却
private val navigateCooldown = Cooldown({ config.cooldownSeconds * 1000L })

if (!navigateCooldown.tryUse(player.uniqueId)) {
    val seconds = navigateCooldown.remainingSeconds(player.uniqueId) // 向上取整,不会提示"还要等 0 秒"
    return
}
```
**被拒绝的尝试不会续期** —— 冷却中反复点击不该把冷却一直往后推。

#### 玩家物品位置枚举 (`PlayerItems` / `ItemSlot`)
每个 `ItemSlot` **自带写回的 setter** —— 主手、副手、四件盔甲、背包下标、末影箱各有各的
放回 API，调用方不必再关心"这一格该用哪个方法写回去"：
```kotlin
for (slot in PlayerItems.allSlots(player)) {   // 背包 + 装备 + 末影箱
    val item = slot.stack ?: continue
    val cleaned = clean(item) ?: continue
    slot.replace(cleaned)                      // 落回原位;传 null 表示清空
    log().info("cleaned ${slot.label}")        // hand / equipment[helmet] / inventory[12] / ender[3]
}
```
也可以只取一类：`handSlot` · `storageSlots` · `equipmentSlots` · `enderSlots`，
或对任意容器用 `inventorySlots(label, inventory)`。

> `storageSlots` 用的是 `storageContents` 而非 `contents`：后者在 `PlayerInventory` 上
> 还会带出盔甲与副手，与 `equipmentSlots` 重复。`allSlots` 同理**不含**主手——它已在背包里。

---

### 2.2 `cubex-config`
提供安全的文件 IO、YAML 默认键自动补充合并以及**基于版本号的配置安全迁移框架**。

#### 版本化配置迁移（`MigrationRunner`）
```kotlin
val runner = MigrationRunner(
    plugin = this,
    plan = MigrationPlan.builder()
        .currentVersion(3)
        .step(1, 2) { ctx ->
            // 从 v1 升到 v2：增加新字段并保留注释
            ctx.yaml.set("new-feature.enabled", true)
        }
        .step(2, 3, LegacyTextToMiniMessageStep("messages.prefix")) // 文本转 MiniMessage
        .build()
)
val report = runner.run(configFile)
```

#### 分阶段重载流（`ReloadChain`）
让服主在执行 `/plugin reload` 时能精准定位是哪一步（配置、语言、数据库还是缓存）发生错误：
```kotlin
val report = ReloadChain.builder()
    .stage("config") { reloadConfig() }
    .stage("i18n") { i18nService.reload() }
    .stage("database") { database.reconnect() }
    .execute()

if (!report.isSuccess) {
    logger.severe("Reload failed at stage: ${report.failedStage}, reason: ${report.error?.message}")
}
```

---

### 2.3 `cubex-i18n`
支持从 `lang/*.yml` 动态加载语言、支持 MiniMessage 与 Legacy 颜色代码混合解析、提供完善的 Fallback 降级链。

#### 初始化与使用
```kotlin
val i18n = I18nService(
    plugin = this,
    options = I18nOptions.builder()
        .defaultLocale("zh_CN")
        .colorMode(ColorMode.MINIMESSAGE) // 或 LEGACY_AND_HEX
        .build()
)
bind(i18n) // 实现 Reloadable，可直接绑定

// 发送消息
i18n.send(player, "contract.created", mapOf("id" to "C123", "amount" to 500))
// 获取 Adventure Component
val component = i18n.component("menu.title")
```

---

### 2.4 `cubex-scheduler`
屏蔽 Paper、Spigot 与 Folia 底层多线程调度差异，提供统一生命期绑定的任务句柄。

```kotlin
val scheduler = CubexScheduler.create(this)

// 1. 全局异步执行
val task = scheduler.runAsync { 
    // 异步任务
}

// 2. 实体域/区域安全调度（适配 Folia）
scheduler.runAtEntity(player) {
    player.sendMessage("Safe in entity region!")
}

// 3. 周期性任务（绑定插件资源栈，自动撤销）
scheduler.runTimer(delayTicks = 20L, periodTicks = 20L) {
    // 周期任务
}.bindTo(this)
```

---

### 2.5 `cubex-integrations`
提供**无编译依赖、基于 ClassLoader 反射**的可选跨插件连接器。

```kotlin
// 从提供方获取服务
val connector = OptionalServiceConnector.connect(
    ServiceDescriptor(
        pluginName = "Reputations",
        apiClassName = "org.cubexmc.reputations.api.ReputationService"
    )
)

when (connector) {
    is ConnectionResult.Connected -> {
        val service = connector.instance
        // 通过窄接口或反射执行交互
    }
    is ConnectionResult.Unavailable -> {
        log().debug("Reputations not available: ${connector.reason}")
    }
}
```

---

### 2.6 `cubex-database`
针对内嵌 SQLite 数据库的标准连接管理与轻量事务封装（不侵入 DAO 或 ORM 结构）。

```kotlin
val db = SQLiteDatabase(
    dataFolder = dataFolder,
    fileName = "storage.db",
    pragmas = SQLitePragmas.builder()
        .wal(true)
        .busyTimeoutMillis(5000)
        .foreignKeys(true)
        .build()
)

// 安全事务执行
JdbcOps.inTransaction(db.connection) { conn ->
    conn.prepareStatement("INSERT INTO logs...").use { stmt ->
        stmt.executeUpdate()
    }
}
```

---

### 2.7 `cubex-command`
用于在插件运行时动态注册与卸载 Bukkit/Paper 命令，避免在重载或禁用时留下死指令条目。

```kotlin
val registrar = CommandRegistrar(this)
// 动态注册指令，返回 Terminable 自动绑定生命期
val handle = registrar.registerDynamicCommand("tempcmd", MyExecutor())
bind(handle) // 插件 disable 时自动从 CommandMap 精确撤销
```

---

### 2.8 `cubex-gui`
基于 Bukkit `Inventory` 实例路由的面向对象 GUI 框架，包含安全附魔物品构建器与纯算法分页器。

```kotlin
// 1. 打开菜单
val menu = Menu.builder(title = "Title", rows = 3)
    .button(slot = 13, InventoryButton.of(
        ItemBuilder(Material.DIAMOND)
            .name("<green>Click Me</green>")
            .glow()
            .build()
    ) { event ->
        event.whoClicked.sendMessage("Clicked!")
    })
    .build()

menuRegistry.open(player, menu)

// 2. 纯算法分页计算（与聊天/GUI通用，不依赖 Bukkit 实例）
val pageCount = Pagination.pageCount(totalItems = 105, pageSize = 10) // 11
val slice = Pagination.slice(list, page = 2, pageSize = 10)
```

#### 铺满空槽 (`fillEmpty`)
```kotlin
// 摆完全部按钮**之后**再调用,否则会把还没放的位置提前占掉
inv.fillEmpty(ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build())
```

#### 聊天提问状态机 (`ChatInputState`)
提问、超时、`cancel`/`clear` 关键字、**两条聊天事件链路的去重**，全在这里，且不碰 Bukkit：
```kotlin
private val state = ChatInputState<(ChatOutcome) -> Unit>()

// 发起提问:载荷(这里是回调)跟提问一起存,提问被顶掉时回调不会悬挂
val prompt = state.open(playerId, allowClear = true, timeoutMillis = 30_000, payload = callback)

// 收到聊天时
when (val result = state.accept(playerId, message)) {
    AcceptResult.NotOurs -> {}                       // 放行给公屏
    AcceptResult.AlreadyTaken -> event.isCancelled = true  // 另一条链路已取走,吞掉但不重复回调
    is AcceptResult.Accepted -> { /* 回主线程 → state.settle(playerId) → result.payload(result.outcome) */ }
}
```
**事件接线留在插件里**：本模块编译到 spigot-api 1.18，引不进 Paper 的 `AsyncChatEvent`；
给模块加 paper-api 会让 1.21 的 API 悄悄漏进所有 1.18 目标的插件。

> ⚠️ **两个聊天事件都必须监听。** Paper 只在没有任何插件监听 legacy `AsyncPlayerChatEvent` 时
> 才走 `AsyncChatEvent`；一旦有人监听 legacy（CMI 很常见），全服都改走 legacy，
> 只监听现代事件的插件一次都收不到 —— 表现为提示词收不到输入、玩家回答被广播到公屏。

#### 现代聊天事件桥 (`ModernChatBridge`)
编译到 **spigot-api** 或把 `net.kyori` **relocate** 过的插件，用它补现代事件监听：
```kotlin
val listener = ModernChatBridge.register(plugin) { player, message -> capture(player, message) }
if (listener != null) plugin.bind(Runnable { ModernChatBridge.unregister(listener) })
```
Spigot 上返回 `null`（安静跳过），Paper 上注册成功。

**为什么是反射**：relocate 过 Adventure 的插件，其编译期 `Component` 与服务器传给事件的
`Component` 是**两个不同的类**，直接调用必然 `NoSuchMethodError`——换 paper-api 也救不了。
本模块不依赖 Adventure，反射按名字解析到的就是服务器那一份。

编译到 paper-api **且不 relocate** Adventure 的插件（Contract、Regions）直接写
`@EventHandler fun onChat(event: AsyncChatEvent)` 更清楚，不必用这个桥。

---

### 2.9 `cubex-spatial`
提供无 Bukkit 依赖的纯领域 3D 空间索引算法（支持 AABB 碰撞检测与并发读写八叉树）。

```kotlin
val octree = Octree<Station>(
    worldMin = Point3D(-10000.0, -64.0, -10000.0),
    worldMax = Point3D(10000.0, 320.0, 10000.0),
    maxDepth = 8,
    maxElementsPerNode = 16
)

octree.insert(Range3D(minPoint, maxPoint), myStation)
val results = octree.getAllRanges(queryAABB)
```

---

### 2.10 `cubex-economy`
Vault 经济封装，外加一条**"玩家付的钱转到哪里去"**的路由。

CubeX 服务器的经济是内循环的：收费插件收走的钱要转进服务器账户，而不是凭空销毁。
本模块把这条路由收敛成一个配置键 `economy.account`，各插件同名同义。

#### 接入（三步）

```kotlin
// 1. enable：hook Vault。返回 null 表示 Vault 或经济提供方缺席。
economyService = VaultEconomy.hook(this, log())
    ?: abortEnable("Vault economy provider not found.")

// 2. enable 与 reload 各解析一次 economy.account。名字解析可能触发一次
//    阻塞的 profile 查询,**不能**放进每次扣款的路径里。
//    useAccount 在"配置没变且上次解析成功"时会整个跳过;上次失败则一定重试,
//    所以服主修好配置后一次 reload 就能救回来。
private fun applyEconomyAccount() {
    val account = try {
        EconomyAccount.parse(config.getString("economy.account", ""))
    } catch (ex: IllegalArgumentException) {
        log().severe("economy.account is invalid; charges will not be banked. ${ex.message}")
        EconomyAccount.None
    }
    economy().useAccount(account)
}

// 3. 收费走 charge():扣款 + 入账一步到位。
val result = economy().charge(player, cost)
if (!result.success()) { /* 玩家付不起,回滚玩法侧 */ }
```

#### `economy.account` 支持的写法

| 配置值 | 含义 |
|---|---|
| 空 / 缺省 | 不入账，扣掉的钱销毁（接入本模块之前的旧行为） |
| `uuid:<uuid>` 或裸 UUID | 按 UUID 精确指定玩家账户（**最稳**） |
| `name:<名字>` | 名字原样交给 Vault 的 name 重载，由经济插件认账户 |
| `<玩家名>` | 先把名字解析成 UUID 再入账 |
| `bank:<名字>` | Vault 的 bank 账户（需要经济插件 `hasBankSupport()`） |

> **Vault 没有"按名字查 UUID"的 API** —— `Economy` 接口里一个返回 UUID 的方法都没有，
> 只有 name 重载和 `OfflinePlayer` 重载。所以 `name:` 不是"用 Vault 查 UUID"，
> 而是**根本不查**：把名字交给经济插件，由它用自己的 name↔账户映射去认。
> 对从不登录的虚拟银行账户来说这是最短的一条路。

#### 两条必须知道的语义

1. **`charge()` 的 `success()` 只表示扣款成不成。** 扣款成功后**一律不回滚**：
   玩家已经消费掉了服务，退款等于白送，而且退款本身同样可能被经济插件拒绝。
   入账失败记 WARNING 并在结果上挂 `depositFailed()`，交给服主对账 ——
   这是唯一一条会让货币总量下降的路径，必须留痕。
2. **按名字解析会识别出"编造的 UUID"并拒绝入账。**
   profile 查不到时 `Bukkit.getOfflinePlayer(name)` 不会失败，而是按
   `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` **编造**一个 v3 UUID ——
   那是另一个账户，静默入账进去是最难发现的一种资金流失。
   模块把这个算法复刻了一份做比对（比"看版本号是不是 4"精确，也不受代理服
   `online-mode=false` 但 UUID 是 v4 的情况干扰）；只有在**离线模式**服务器上
   编造出来的 UUID 才是正确答案，那时才接受。
   解析顺序：在线玩家 → Paper `getOfflinePlayerIfCached`（纯查 usercache）→
   `getOfflinePlayer(name)`（在线模式下即一次 profile 查询，查到后服务器自己写进 `usercache.json`）。

> 模块**不用** `Bukkit.getOfflinePlayers()`：那个方法每次调用都要列一遍 `playerdata` 目录，
> 在主线程上是 O(存档数)。

---

## 3. 开发最佳实践与避坑指南

1. **共享模块中严禁包含业务状态**：
   - 内嵌模式下共享模块代码会被 shade 进各独立插件 jar，必须保持为纯工具类、无状态服务或生命周期模板。
   - 需要持有**跨插件共享的运行时状态**的能力不进 `modules/`，进 `CubeXLib`（单实例）——
     见 `AGENTS.md` 硬约束与 `PLAN.md` §7.1。
2. **新增模块的门槛**：以 `PLAN.md` §3.2 的四条为准（真实使用方 / 有成熟三方库就封装不重造 /
   无状态才能 shade / 落地时配一篇可编译范例）。原来只有"至少 2 个真实使用方"这一条，已不再是全部。
3. **资源绑定优先使用 `bind(...)`**：
   - 避免在 `disablePlugin()` 中手写大段的手动 close 逻辑。让 Store、Service、Task 实现 `Terminable` / `AutoCloseable` 并在创建后立即 `bind(this)`。
4. **I18n 消息全面 MiniMessage 化**：
   - 新增配置文件与语言文件统一采用 MiniMessage 格式（如 `<green>Text</green>`），占位符推荐采用 `<param>` 或 `%param%` 规范。
