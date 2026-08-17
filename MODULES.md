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
| [`cubex-gui`](modules/cubex-gui) | 界面交互：基于 Inventory 实例事件路由的 Menu 框架、ItemBuilder、Pagination | **3/12** | Contract, Metro, Railway |
| [`cubex-spatial`](modules/cubex-spatial) | 空间索引：Point3D, Range3D (AABB), Octree 八叉树索引 | **2/12** | Metro, Railway |

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

## 3. 开发最佳实践与避坑指南

1. **共享模块中严禁包含业务状态**：
   - 共享模块代码会被 shade 进各独立插件 jar，必须保持为纯工具类、无状态服务或生命周期模板。
2. **新增模块的门槛**：
   - 必须满足 **至少 2 个真实插件使用方** 且存在实测重复代码时才允许新建模块。
3. **资源绑定优先使用 `bind(...)`**：
   - 避免在 `disablePlugin()` 中手写大段的手动 close 逻辑。让 Store、Service、Task 实现 `Terminable` / `AutoCloseable` 并在创建后立即 `bind(this)`。
4. **I18n 消息全面 MiniMessage 化**：
   - 新增配置文件与语言文件统一采用 MiniMessage 格式（如 `<green>Text</green>`），占位符推荐采用 `<param>` 或 `%param%` 规范。
