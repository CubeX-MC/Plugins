# CubeX Core Design Draft

本文件是第 2 阶段的 API 设计稿,仅基于当前 8 个插件源码中的真实重复提案;本轮不实现 `modules/*`,也不修改任何插件代码。

## 0. 设计约束

- `cubex-core` 保持 Java,小而必选;`cubex-config`、`cubex-i18n`、`cubex-database`、`cubex-command` 按需引入。
- 目标是接入时零行为变更:现有 `plugin.yml`、命令、权限、配置路径、语言 key、数据库文件名、调度语义都必须保持。
- 不把领域逻辑下沉到 core。尤其 Metro/Railway 的 Stop/Line/Portal/GUI/物理/发车逻辑继续各自拥有。
- 不新增第 1 阶段禁止的依赖升级;database 模块先只封装当前 SQLite/JDBC 共性,不引入 HikariCP。
- 共享模块最终随各插件 shade 进 jar;sqlite-jdbc 仍按现有过滤器处理,绝不 relocate sqlite。

## 1. 实测重复清单

扫描范围:

- `BookLite/src/main/java`
- `FAWEReplacer/src/main/java`
- `MountLicense/src/main/java`
- `Contracts/src/main/java`
- `EcoBalancer/src/main/java`
- `RuleGems/src/main/java`
- `Metro/src/main/java`
- `Railway/src/main/java`

粗略命中数来自 `rg` 模式扫描,用于判断重复热度,最终边界仍以代码形态为准。

| 关注点 | 每插件命中文件数 | 初判 |
|---|---:|---|
| 生命周期 | 8/8 主类 | 高重复,适合 core 托管骨架 |
| 消息/颜色 | 144 文件 | 高重复,但组件形态不同;core 放低层颜色/发送工具,i18n 单独做 |
| 配置 | 83 文件 | 中高重复;默认文件/合并可抽,强类型配置仍插件自管 |
| i18n | 8/8 有 `lang/*.yml` | 高重复,但 key 和 placeholder 差异大;做可选模块 |
| 资源清理 | 122 文件 | 高重复,适合 Terminable/AutoCloseable 绑定 |
| 命令/Tab | 34 文件 | 重复但框架差异大;只做可选注册脚手架 |
| 数据库 | 3 个插件实质 SQLite | 可抽连接工厂/PRAGMA,不抽 DAO/Schema |

### 1.1 生命周期

谁在用:

| 插件 | 证据 | 共同模式 | 差异 |
|---|---|---|---|
| BookLite | `BookLite/src/main/java/org/cubexmc/booklite/BookLitePlugin.java:16`, `:28`, `:64` | `JavaPlugin` 主类,保存默认资源,构造 manager,注册 listener/command,启动 Metrics,disable 关闭 repository | 主类短,仅 `repository.close()` |
| FAWEReplacer | `FAWEReplacer/src/main/java/org/cubexmc/fawereplace/FAWEReplace.java:26`, `:35`, `:79` | 保存默认 config,加载语言,注册命令,启动 Metrics | 动态注册 `CommandMap`;禁用时停止清理任务 |
| MountLicense | `MountLicense/src/main/java/org/cubexmc/mountlicense/MountLicensePlugin.java:22`, `:36`, `:86` | 保存默认资源,构造 manager/service,注册 listener/command,Metrics | disable flush `VehicleIndex` |
| Contracts | `Contracts/src/main/java/org/cubexmc/contract/ContractPlugin.java:17`, `:27`, `:63` | 保存默认文件,加载语言,构造 service/gui,注册 command/listener,Metrics | Vault 缺失时 disable 自身;disable 关闭 GUI session 并保存 storage |
| EcoBalancer | `EcoBalancer/src/main/java/org/cubexmc/ecobalancer/EcoBalancer.java:49`, `:137`, `:365` | 初始化集成/config/db/scheduler/command/Metrics,禁用时清资源 | Vault/Permission、file logger、SQLite 初始化、定时任务较重 |
| RuleGems | `RuleGems/src/main/java/org/cubexmc/RuleGems.java:41`, `:68`, `:177` | 构造 manager,注册命令/listener,启动任务,Metrics,disable 保存/清理 | Cloud commands + 自定义 CommandMap proxy + 功能系统 |
| Metro | `Metro/src/main/java/org/cubexmc/metro/Metro.java:42`, `:74`, `:172` | config/i18n/manager/listener/command/lifecycle/metrics/API 初始化,disable 多资源清理 | 体量大,Folia 分支,领域清理复杂 |
| Railway | `Railway/src/main/java/org/cubexmc/metro/Metro.java:48`, `:82`, `:205` | 与 Metro 同类骨架 | 额外实体模型、估时、PlaceholderAPI、tick counter |

相似度: 中高。8 个主类都有 `onEnable/onDisable`,但初始化顺序和失败路径必须保留。适合统一的是"生命周期外壳":异常记录、自身禁用、资源栈关闭、便捷注册;不适合统一 manager 构造顺序。

结论: `cubex-core` 提供 `CubexPlugin` 基类,以 `enablePlugin()/disablePlugin()` 承载原逻辑,`onEnable/onDisable` 由基类托管。接入时逐插件小步迁移,不得重排业务初始化。

### 1.2 日志

谁在用:

- 8 个插件都直接用 `getLogger().info/warning/severe/log`。
- BookLite/MountLicense 用固定启停文本: `BookLitePlugin.java:60`, `MountLicensePlugin.java:82`。
- Contracts 在依赖缺失和保存失败时直接记录英文文本: `ContractPlugin.java:35`, `:71`。
- EcoBalancer 有额外文件日志器: `EcoBalancer.java:52-53`, 初始化/关闭在 `:81-132`, `:365-370`。
- RuleGems 有多行安全警告和语言化日志: `RuleGems.java:103-109`, `:145-146`, `:190-191`。
- Metro/Railway 既用 `getLogger()` 也向 console 发送语言消息: `Metro.java:168`, `:227-230`; `Railway/.../Metro.java:201`, `:260` 起。

相似度: 低到中。所有插件都要日志,但文本、语言化程度、文件 logger 差异大。

结论: core 只提供薄 `CubexLogger` 包装,默认仍代理 Bukkit logger,可自动带插件名/版本上下文;EcoBalancer 的文件 logger 先留插件内,不进入 core。

### 1.3 消息与颜色处理

谁在用:

- BookLite `LanguageManager` 使用 `ChatColor.translateAlternateColorCodes('&', ...)`,支持 `{prefix}` 和 `%key%`: `BookLite/src/main/java/org/cubexmc/booklite/lang/LanguageManager.java:75-88`。
- MountLicense 与 BookLite 高度相似,并额外支持 actionbar: `MountLicense/src/main/java/org/cubexmc/mountlicense/lang/LanguageManager.java:75-106`。
- Contracts 有十六进制颜色与控制字符清理工具: `Contracts/src/main/java/org/cubexmc/contract/util/Text.java:8-31`。
- EcoBalancer `MessageUtils` 支持 `%placeholder%`,Bungee `TextComponent` 点击/悬停组件: `EcoBalancer/src/main/java/org/cubexmc/ecobalancer/utils/MessageUtils.java:28-42`, `:54-110`, `:119-147`。
- RuleGems `ColorUtils` 支持 `&#RRGGBB` 和 legacy `&`: `RuleGems/src/main/java/org/cubexmc/utils/ColorUtils.java:8-38`。
- Metro/Railway `ColorUtil` 与 RuleGems/Contracts 的 hex+legacy 模式高度相似: `Metro/src/main/java/org/cubexmc/metro/util/ColorUtil.java:11-39`, `Railway/src/main/java/org/cubexmc/metro/util/ColorUtil.java:11-39`。
- 当前源码未发现 MiniMessage 作为主路径;主要是 Bukkit/Bungee legacy color + Bungee component。

相似度: 颜色转换高,消息格式中,富文本组件低。

结论: `cubex-core` 放 `CubexText`/`Messager` 的低层能力: legacy `&`、`&#RRGGBB`、按行发送、空值策略。i18n、click/hover component、actionbar 作为 `cubex-i18n` 或插件侧适配,避免把 core 做成消息 god-object。

### 1.4 配置加载、默认值合并、重载

谁在用:

- BookLite: `BookLitePlugin.saveDefaultResources()` 保存 `config.yml` 和两份语言文件;`reloadAll()` 重载配置/i18n,并在 SQLite 文件或 WAL 变化时重开 repository: `BookLitePlugin.java:68-88`。强类型读取在 `BookLite/config/ConfigManager.java:34-60`。
- FAWEReplacer: `saveDefaultConfig()`,手动保存/加载 `rules.yml`,并迁移旧数据: `FAWEReplace.java:40-52`, `:120` 起。
- MountLicense: `saveDefaultResources()` + `ConfigManager.load()` 强类型缓存大量配置: `MountLicensePlugin.java:36-43`, `MountLicense/config/ConfigManager.java:67-130`。
- Contracts: `saveDefaultFiles()`,保存语言,`reloadContracts()` 关闭 GUI session 后重载 config/lang/storage: `ContractPlugin.java:76-84`, `:106-120`。
- EcoBalancer: 先 `ConfigMigrator.migrateConfig()`,再 `saveDefaultConfig()`、`loadConfiguration()`;reload 时取消任务、加载 lang、更新 file logger、重新 schedule: `EcoBalancer.java:158-169`, `:306-323`。
- RuleGems: `loadPlugin()` 做 `saveDefaultConfig()`、`reloadConfig()`、更新内置语言、加载多个 feature/gem 配置和数据: `RuleGems.java:198-221`。
- Metro/Railway: `ConfigUpdater.applyDefaults(...)`, `ConfigFacade.reload()`, `DataFileUpdater.migrateAll(...)`;`ConfigFacade` 是大量强类型缓存: `Metro/.../Metro.java:80-90`, `Railway/.../Metro.java:88-99`, `Metro/config/ConfigFacade.java:13` 起。

相似度: 默认资源保存/新增 key 合并高;强类型配置字段低。

结论: `cubex-config` 只抽"资源文件存在性、默认值合并、reload 生命周期钩子、Yaml 读取便利函数"。各插件的 `ConfigManager/ConfigFacade` 继续保留自己的字段和验证逻辑。

### 1.5 i18n

谁在用:

- 8 个插件都有 `src/main/resources/lang/*.yml`。BookLite/FAWEReplacer/MountLicense/Contracts/EcoBalancer/RuleGems 至少有 `en_US.yml` 和 `zh_CN.yml`; Metro/Railway 各有 `de_DE/en_US/es_ES/nl_NL/tr_TR/zh_CN/zh_TW`。
- BookLite/MountLicense: 单当前语言文件,缺失回退 `zh_CN`,`{prefix}` + `%key%`: `BookLite/lang/LanguageManager.java:30-88`, `MountLicense/lang/LanguageManager.java:34-106`。
- Contracts: 从 `config.language` 选择文件,消息 key 带 `messages.` 前缀,并有 enum label helper: `Contracts/config/LanguageManager.java:24-83`。
- EcoBalancer: 主类内 `loadLangFile()` 读取 `lang/<language>.yml`,消息经 `MessageUtils`: `EcoBalancer.java:325-343`。
- RuleGems: `LanguageManager` 更新内置语言并支持更多格式化入口,调用点分布很广。
- Metro/Railway: `LanguageManager` 加载所有 yml,默认语言来自 `settings.default_language`,缺失返回 `"Missing message: key"`,并合并新增语言 key: `Metro/manager/LanguageManager.java:39-105`, `:114-140`; Railway 同形。

相似度: 文件布局高,placeholder/key 约定中,高级能力低。

结论: `cubex-i18n` 做可配置 `I18nService`,支持:

- `lang/<locale>.yml` 复制/合并;
- 默认语言与当前语言;
- fallback 语言;
- legacy/hex color;
- `%name%` 和 `{name}` 两类 placeholder 策略;
- 缺失 key 的可配置 fallback 文本。

不做: 自动推断各插件 key 前缀、枚举 label 业务方法、EcoBalancer clickable component DSL。

### 1.6 资源清理

谁在用:

- BookLite 手动关闭 SQLite 连接: `BookLitePlugin.java:64-66`, `BookRepository.java:76-83`。
- FAWEReplacer 停止清理任务: `FAWEReplace.java:79-84`。
- MountLicense disable flush 索引: `MountLicensePlugin.java:86-89`。
- Contracts close GUI sessions 并保存 storage: `ContractPlugin.java:63-73`。
- EcoBalancer reload 会 `SchedulerUtils.cancelAllTasks(this)`,disable 关闭 `FileHandler`: `EcoBalancer.java:306-313`, `:365-370`。
- RuleGems shutdown feature、unregister proxy command、保存 gems: `RuleGems.java:177-192`。
- Metro/Railway disable 关闭 map lifecycle、listener、scoreboard、scoreboard library、train task、scheduled lifecycle、route recorder,并 flush 数据: `Metro/.../Metro.java:172-225`, `Railway/.../Metro.java:205-258`。
- Metro/Railway 已有可下沉的 task lifecycle 形态: `ScheduledTaskLifecycle.shutdown()` 取消两个 task: `Metro/lifecycle/ScheduledTaskLifecycle.java:58-67`; `MapIntegrationLifecycle.disable()` 取消 refresh 并 disable integration: `Metro/lifecycle/MapIntegrationLifecycle.java:43-50`。

相似度: 高。清理对象类型不同,但"注册时绑定,disable 时按顺序关闭/取消"一致。

结论: `cubex-core` 需要 Java 版 Terminable/AutoCloseable 资源栈。允许绑定 `AutoCloseable`、`Runnable` close hook、Bukkit task/Folia task handle。默认按 LIFO 关闭,保证后创建的服务先释放。

### 1.7 命令与 Tab 补全注册

谁在用:

- BookLite/MountLicense/Contracts: `getCommand(...).setExecutor(...).setTabCompleter(...)`: `BookLitePlugin.java:50-56`, `MountLicensePlugin.java:73-78`, `ContractPlugin.java:49-54`。
- FAWEReplacer: 手动创建 `org.bukkit.command.Command` 并注册到 `CommandMap`,设置 alias/permission/usage: `FAWEReplace.java:90-117`。
- EcoBalancer: 一个 root `UtilCommand` 分发大量子命令,另有 `EcoTabCompleter`: `EcoBalancer.java:222-229`, `EcoBalancer/commands/UtilCommand.java`。
- RuleGems: 使用 `CloudCommandManager.registerAll()`,还维护可动态刷新的 proxy command: `RuleGems.java:87-88`, `:143`, `:183-186`。
- Metro/Railway: `CommandRegistration` 封装 Cloud command 和 Bukkit fallback: `Metro/.../Metro.java:140-146`, `Railway/.../Metro.java:151-157`, `Metro/lifecycle/CommandRegistration.java`。

相似度: 注册错误处理和 null-safe 注册中;命令框架/分发模型低。

结论: `cubex-command` 先只做薄脚手架:

- `PluginCommand` null-safe 注册;
- executor/tab completer 同对象注册;
- 可选动态 `CommandMap` 注册/撤销 hook;
- Cloud 框架 bridge 先不抽公共 DSL,最多提供生命周期绑定接口。

### 1.8 数据库连接

谁在用:

- BookLite: 持久 `Connection`,启动时 `Class.forName("org.sqlite.JDBC")`,按配置文件名打开,设置 `busy_timeout/WAL/synchronous/foreign_keys`,disable 关闭: `BookLite/storage/BookRepository.java:34-48`, `:76-83`。
- EcoBalancer: 每次操作打开连接,固定 `records.db`,每连接设置 PRAGMA,初始化大量 schema/index: `EcoBalancer/utils/DatabaseUtils.java:14-45`, `:53-150`。
- RuleGems: 每次操作打开连接,数据库路径可配置,保存 YAML payload 到单表,初始化时从 YAML 迁移: `RuleGems/storage/SqliteStorageProvider.java:44-64`, `:106-118`, `:120-136`。
- 其余插件未发现实质 JDBC 存储。部分命中来自 Metrics 或普通 `Connection` 字样,不作为 database 抽取依据。

相似度: SQLite 文件解析/连接/PRAGMA 中;schema/DAO 低。

结论: `cubex-database` 只提供 SQLite 连接工厂和 PRAGMA 配置。DAO、表结构、迁移策略、事务边界全部留在插件内。当前没有 Hikari 实测需求,第 2 阶段不引入 Hikari。

## 2. 模块边界提案

### 2.1 `cubex-core` 必选

职责:

- `CubexPlugin` 生命周期模板: `onEnable/onDisable` 托管、异常记录、自身禁用、资源栈关闭。
- Java 版 Terminable: `AutoCloseable`/`Runnable`/task handle 绑定,disable 自动清理。
- `CubexLogger`: 轻量日志包装,默认代理 Bukkit logger,提供一致的 `info/warn/severe/debug`。
- `CubexText`: legacy `&`、hex `&#RRGGBB`、空值策略、控制字符清理。
- `Messager`: 向 `CommandSender` 按行发送字符串;不持有语言文件。
- `SchedulerHandle`/`SchedulerFacade`: 当前只承接 Folia/Bukkit task handle 的取消绑定;完整调度 DSL 可在 Metro/Railway 接入时再扩。

不纳入:

- 任何插件的 manager 构造顺序。
- bStats 初始化。
- Vault/PlaceholderAPI/WorldEdit/地图/Scoreboard/CloudCommand 等第三方集成。
- EcoBalancer 文件 logger。
- Metro/Railway 领域生命周期如列车清理、地图 provider 选择。

### 2.2 `cubex-config` 可选

职责:

- 保存缺失资源: `config.yml`, `lang/*.yml`, `lines.yml`, `stops.yml`, feature yml 等。
- YAML 默认值合并: 从 jar resource 合并新增 key,不覆盖用户已有值。
- `Reloadable` 协议: 让插件自己的 manager 可以被主类统一调用。
- 类型读取小工具: `getString/getBoolean/getInt/getEnum/getMaterial` 带 fallback,但不缓存业务字段。

不纳入:

- BookLite/MountLicense/Metro/Railway 的强类型 `ConfigManager/ConfigFacade` 字段。
- EcoBalancer/RuleGems 的具体 config migration。
- 数据文件迁移如 Metro `DataFileUpdater`。

### 2.3 `cubex-i18n` 可选

职责:

- 管理 `lang/*.yml` 的复制、合并、加载、fallback。
- 提供 `message(key)`, `message(key, placeholders)`, `list(key, placeholders)`, `send(...)`。
- 支持 `%name%` 与 `{name}` placeholder 策略,以及 prefix token 兼容。
- 使用 `CubexText` 做 legacy/hex color。

不纳入:

- 插件业务枚举 label 方法,如 Contracts `status/type/role/condition`。
- EcoBalancer click/hover component 的业务拼装。
- MiniMessage;当前没有实测使用。

### 2.4 `cubex-database` 可选

职责:

- SQLite 数据库文件解析: 相对路径基于 `plugin.getDataFolder()`。
- `SQLiteConnectionFactory` 打开连接并套用 PRAGMA。
- 提供 `SQLitePragmas` builder,允许 BookLite/EcoBalancer/RuleGems 保持不同 busy timeout/WAL/cache 设置。
- 提供 `JdbcOps.withConnection(...)` 小工具,减少 try-with-resources 样板。

不纳入:

- HikariCP;当前没有实测使用。
- DAO、schema、迁移、事务重试。
- sqlite-jdbc shade/relocate 策略;这仍属于各插件 build 配置和 §1 护栏。

### 2.5 `cubex-command` 可选

职责:

- null-safe 注册 `PluginCommand` 的 executor/tab completer。
- 动态 `CommandMap` 注册/撤销的薄包装,用于 FAWEReplacer/RuleGems 这类现有模式。
- 命令资源绑定: 动态注册的命令可绑定到 `CubexPlugin` disable 时撤销。

不纳入:

- 新命令 DSL。
- Cloud annotations 的统一封装。
- 子命令路由模型;EcoBalancer/RuleGems/Metro/Railway 保持各自框架。

## 3. Java API 契约草案

以下为签名和职责说明,不是实现代码。

### 3.1 `cubex-core`

```java
package org.cubexmc.core;

import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public interface Terminable extends AutoCloseable {
    @Override
    void close() throws Exception;

    static Terminable of(Runnable closeAction);
}

public interface TerminableConsumer {
    <T extends AutoCloseable> T bind(T terminable);

    Terminable bind(Runnable closeAction);
}

public abstract class CubexPlugin extends JavaPlugin implements TerminableConsumer {
    @Override
    public final void onEnable();

    @Override
    public final void onDisable();

    protected abstract void enablePlugin() throws Exception;

    protected void disablePlugin() throws Exception;

    protected void onEnableFailure(Throwable throwable);

    protected CubexLogger log();

    protected Messager messager();

    protected CubexText text();

    protected void registerListener(Listener listener);

    protected void saveResourcesIfMissing(String... resourcePaths);

    protected Terminable bindTask(Object taskHandle, TaskCanceller canceller);
}

@FunctionalInterface
public interface TaskCanceller {
    void cancel(Object taskHandle) throws Exception;
}

public final class CubexLogger {
    public void info(String message);
    public void warn(String message);
    public void warn(String message, Throwable throwable);
    public void severe(String message);
    public void severe(String message, Throwable throwable);
    public void debug(String message);
    public void log(Level level, String message, Throwable throwable);
}

public final class CubexText {
    public String color(String input);
    public String stripControl(String input);
    public String nullToEmpty(String input);
}

public final class Messager {
    public void send(CommandSender target, String message);
    public void sendLines(CommandSender target, Iterable<String> messages);
}
```

行为契约:

- `onEnable()` 调用 `enablePlugin()`;若抛异常,记录 `SEVERE`,调用 `onEnableFailure`,并禁用插件。
- `onDisable()` 先调用 `disablePlugin()`,再按 LIFO 关闭 `bind(...)` 注册的资源;关闭异常记录但继续关闭剩余资源。
- `registerListener(listener)` 只是 `PluginManager.registerEvents(listener, this)` 的薄包装;Bukkit 原生 listener 自动解绑语义不变。
- `saveResourcesIfMissing(...)` 只在目标文件不存在时 `saveResource(path, false)`,不覆盖用户文件。
- `CubexText.color` 保持现有 legacy `&` 和 `&#RRGGBB` 语义;不引入 MiniMessage。

### 3.2 `cubex-config`

```java
package org.cubexmc.config;

import java.io.File;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public interface Reloadable {
    void reload();
}

public final class ResourceFiles {
    public static File saveIfMissing(JavaPlugin plugin, String resourcePath);
    public static File dataFile(JavaPlugin plugin, String relativePath);
}

public final class YamlDefaults {
    public static boolean mergeMissingKeys(JavaPlugin plugin, File targetFile, String resourcePath);
    public static boolean mergeMissingKeys(JavaPlugin plugin, File targetFile, String resourcePath,
            MergeOptions options);
}

public final class MergeOptions {
    public static MergeOptions preserveUserValues();
}

public final class ConfigReader {
    public ConfigReader(FileConfiguration config);

    public String string(String path, String fallback);
    public boolean bool(String path, boolean fallback);
    public int integer(String path, int fallback);
    public long longValue(String path, long fallback);
    public double decimal(String path, double fallback);
    public Material material(String path, Material fallback);
    public <E extends Enum<E>> E enumValue(String path, Class<E> enumType, E fallback);
}
```

### 3.3 `cubex-i18n`

```java
package org.cubexmc.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class I18nOptions {
    public static Builder builder(JavaPlugin plugin);

    public static final class Builder {
        public Builder resourceDirectory(String directory);
        public Builder bundledLocales(String... locales);
        public Builder defaultLocale(String locale);
        public Builder fallbackLocale(String locale);
        public Builder missingKeyPrefix(String prefix);
        public Builder prefixToken(String token);
        public Builder placeholderStyle(PlaceholderStyle style);
        public I18nOptions build();
    }
}

public enum PlaceholderStyle {
    PERCENT, // %name%
    BRACE,   // {name}
    BOTH
}

public final class I18nService {
    public I18nService(I18nOptions options);

    public void load();
    public void reload();
    public void setLocale(String locale);
    public String locale();
    public String message(String key);
    public String message(String key, Map<String, String> placeholders);
    public List<String> list(String key, Map<String, String> placeholders);
    public void send(CommandSender target, String key);
    public void send(CommandSender target, String key, Map<String, String> placeholders);
}
```

### 3.4 `cubex-database`

```java
package org.cubexmc.database;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import org.bukkit.plugin.Plugin;

public final class SQLiteDatabase {
    public SQLiteDatabase(Plugin plugin, String relativeOrAbsolutePath, SQLitePragmas pragmas);

    public File file();
    public Connection openConnection() throws SQLException;
}

public final class SQLitePragmas {
    public static Builder builder();

    public static final class Builder {
        public Builder busyTimeoutMillis(int millis);
        public Builder wal(boolean enabled);
        public Builder synchronous(String value);
        public Builder foreignKeys(boolean enabled);
        public Builder tempStoreMemory(boolean enabled);
        public Builder cacheSizeKb(int kilobytes);
        public SQLitePragmas build();
    }
}

@FunctionalInterface
public interface SQLFunction<T> {
    T apply(Connection connection) throws SQLException;
}

public final class JdbcOps {
    public static <T> T withConnection(SQLiteDatabase database, SQLFunction<T> action) throws SQLException;
}
```

### 3.5 `cubex-command`

```java
package org.cubexmc.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.core.Terminable;
import org.cubexmc.core.TerminableConsumer;

public final class CommandRegistrar {
    public CommandRegistrar(JavaPlugin plugin, TerminableConsumer terminables);

    public PluginCommand registerPluginCommand(String name, CommandExecutor executor);

    public PluginCommand registerPluginCommand(String name, CommandExecutor executor, TabCompleter tabCompleter);

    public Terminable registerDynamicCommand(String fallbackPrefix, Command command);
}
```

行为契约:

- `registerPluginCommand` 若 `plugin.yml` 未声明该命令,记录 warning 或抛 `IllegalStateException` 由调用方选择;默认不静默吞掉。
- `registerDynamicCommand` 返回可关闭资源,disable 时撤销命令映射,用于 RuleGems proxy 和 FAWEReplacer 动态命令。
- 不替换 CloudCommandManager;Cloud 注册器接入时只把它作为插件自己的资源绑定。

## 4. BookLite 接入示意

目标: 不改变 BookLite 的资源文件、语言 key、SQLite 文件、WAL 行为、命令名、权限、listener、Metrics ID。

伪代码:

```java
public final class BookLitePlugin extends CubexPlugin {
    private ConfigManager configManager;
    private I18nService language;
    private BookRepository repository;
    private BookCache cache;
    private BookService bookService;

    @Override
    protected void enablePlugin() {
        saveResourcesIfMissing("config.yml", "lang/zh_CN.yml", "lang/en_US.yml");

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.language = new I18nService(I18nOptions.builder(this)
                .resourceDirectory("lang")
                .bundledLocales("zh_CN", "en_US")
                .defaultLocale(configManager.getLanguage())
                .fallbackLocale("zh_CN")
                .prefixToken("{prefix}")
                .placeholderStyle(PlaceholderStyle.PERCENT)
                .build());
        this.language.load();

        this.repository = new BookRepository(this, configManager);
        this.repository.init();
        bind((AutoCloseable) repository::close);

        this.cache = new BookCache(configManager.getCacheMaximumSize(),
                configManager.getCacheExpireAfterAccessMillis());
        BookCodec codec = new BookCodec(this, new PdcKeys(this), configManager);
        BookRestorer restorer = new BookRestorer(this, bookService, codec);
        this.bookService = new BookService(this, repository, cache);

        registerListener(new BookListener(this, bookService, codec, restorer, /* adapter */ languageManager()));

        BookLiteCommand executor = new BookLiteCommand(this, bookService, codec, restorer, /* adapter */ languageManager());
        new CommandRegistrar(this, this).registerPluginCommand("booklite", executor, executor);

        new Metrics(this, 31451);
        log().info("BookLite " + getDescription().getVersion() + " enabled.");
    }

    @Override
    protected void disablePlugin() {
        // repository is closed by bind(...); no behavior change.
    }

    public void reloadAll() {
        String oldSqliteFile = configManager.getSqliteFile();
        boolean oldWal = configManager.isWal();
        saveResourcesIfMissing("config.yml", "lang/zh_CN.yml", "lang/en_US.yml");
        reloadConfig();
        configManager.load();
        language.setLocale(configManager.getLanguage());
        language.reload();
        if (!oldSqliteFile.equals(configManager.getSqliteFile()) || oldWal != configManager.isWal()) {
            repository.close();
            repository.init();
            cache.clear();
        }
        cache.resize(configManager.getCacheMaximumSize(), configManager.getCacheExpireAfterAccessMillis());
    }
}
```

行为不变论证:

- `saveResourcesIfMissing(...)` 等价于当前 `saveDefaultConfig()` + `saveResourceIfMissing(...)`,仍不覆盖用户文件。
- `ConfigManager` 暂时不抽,读取路径/default 完全保留。
- i18n 需要提供 BookLite 兼容 adapter,保持 `{prefix}` 和 `%placeholder%`。
- SQLite 仍由 `BookRepository` 负责 schema/PRAGMA/查询;core 只托管 close hook。
- `booklite` 命令仍来自 `plugin.yml`,executor/tab completer 同一对象。
- Metrics ID `31451` 保留。

## 5. 风险与取舍

### 5.1 过度封装风险

- 命令 DSL 风险高: 当前同时存在 Bukkit executor、动态 CommandMap、Cloud framework、proxy command。第 2 阶段只做注册 helper。
- ConfigFacade 风险高: Metro/Railway 配置字段多且带兼容 fallback;强行统一会改变边界。只抽默认合并和读取小工具。
- i18n 风险中: 各插件 placeholder/token/fallback 不同。模块必须可配置,并允许插件保留 adapter。
- Database 风险中: SQLite schema 和连接生命周期差异明显。只抽连接工厂和 PRAGMA。
- Scheduler 风险中: Folia 反射逻辑已在 EcoBalancer/RuleGems/Metro/Railway 有不同版本。core 先承接 task handle 绑定;完整调度 API 等接入 Metro/Railway 前再 review。
- Logger 风险中: EcoBalancer 文件日志器是业务/运维特性,不应被 core logger 吃掉。

### 5.2 建议保留插件自管

- bStats 初始化和 pluginId。
- Vault/Permission/PlaceholderAPI/WorldEdit/地图/Scoreboard 等第三方集成。
- 所有数据 schema、迁移和 DAO。
- Metro/Railway 的列车、站点、路线、传送门、GUI、物理、发车逻辑。
- RuleGems 的 Cloud command 语义、动态 proxy command 业务规则。
- Contracts 的合同状态/type/role/condition 文案 helper。

### 5.3 第 2 阶段实现顺序建议

1. 只建 `modules/cubex-core`,实现 `CubexPlugin`、Terminable、logger、text、messager,加单测。
2. BookLite 试点只接 `CubexPlugin` + `bind(repository::close)` + `saveResourcesIfMissing`,暂不替换 i18n/config/database。
3. 行为等价后再补 `cubex-config` 和 `cubex-i18n`,仍以 BookLite 为试点。
4. `cubex-database` 等 BookLite/EcoBalancer/RuleGems 三者都准备接入时再实现。
5. `cubex-command` 等 FAWEReplacer/RuleGems/Metro/Railway 接入前再细化动态命令撤销契约。

## 6. Review 待确认点

- `CubexPlugin.onEnable/onDisable` 是否应为 `final`: final 能保证资源关闭,但会要求所有插件迁到 `enablePlugin/disablePlugin` 模板。
- `CubexPlugin` enable 异常的默认行为: 本草案建议记录并禁用插件;需确认是否与现有失败语义可接受。
- `CubexText.color(null)` 返回空字符串还是 null: 当前 BookLite/Contracts 倾向空字符串,RuleGems 保留 null。建议 core 提供 `colorOrNull` 与 `color` 两个入口。
- `cubex-i18n` 是否第一批就做,还是 BookLite 试点先保留原 `LanguageManager`。
- `SchedulerFacade` 是否属于 core 第一批实现,还是先只提供 `bindTask`。

## 7. Review 结论(API 把关人已确认,实现以此为准)

**总体:方向通过。** 设计基于实测、分层+可选、明确反过度封装,符合要求。以下为对 §6 的裁定与补充约束:

**§6 裁定**
1. `onEnable/onDisable` **保持 `final`**(这正是统一生命周期的价值)。为承接"中途放弃 enable"(如 Contracts 缺 Vault 即禁用):允许 `enablePlugin()` 抛异常,或提供 `protected void abortEnable(String reason)` 助手(记录原因并 `setEnabled(false)`,不当作错误)。两者择一实现,推荐都给。
2. enable 异常默认 **记录 SEVERE + 禁用插件**:通过。比现状更干净(失败的插件本就处于半残状态)。
3. `CubexText`:**同时提供 `color`(null→"")与 `colorOrNull`(保留 null)**,各插件按现状选。通过。
4. `cubex-i18n` **第一批不做**:BookLite 首个试点保留其原 `LanguageManager`/`ConfigManager` 不动(见下补充B)。
5. `SchedulerFacade` **第一批不做**:core 首批只提供 `bindTask`;完整调度 API 待 Metro/Railway 接入前再 review。

**补充约束(实现时遵守)**
- **A. 第一批只实现 `modules/cubex-core`**。本文 §3.2~§3.5 的 config/i18n/database/command API 是路线图,**不在本轮实现**;各自等到有插件实际接入那一步再建,避免投机。
- **B. BookLite 首个试点保持最小**:仅 `extends CubexPlugin` + 把 `repository::close` 改为 `bind(...)` + `saveResourcesIfMissing(...)`;**不替换** i18n/config/database/命令注册。§4 示意图是"最终形态",首轮**不照搬**,以 §5.3 步骤 2 为准。
- **C. `bind` 人体工学**:提供干净的 `bind(Runnable closeAction)` 重载,避免 `bind((AutoCloseable) repository::close)` 这种丑转换。
- **D. 双重关闭**:接入时每个资源的清理只放在 `bind(...)` 或 `disablePlugin()` 之一,**绝不两处都做**(防 double-close)。
- **E. YAML 合并警示(留给 cubex-config 阶段)**:Bukkit `YamlConfiguration` 保存会**丢注释**;若某插件配置依赖注释或有自己的 `ConfigUpdater/Migrator`(EcoBalancer/Metro),**保留其自有逻辑**,不用通用合并。
- **F. 打包**:每个 public 类型一个 `.java` 文件(本文为阅读把多个类型并列展示)。

**验收(本批 = cubex-core + BookLite 最小试点)**:`:modules:cubex-core:build` 通过且核心工具有单测;BookLite 接入后 `runServer` 行为与接入前一致;`plugin.yml`/命令/权限/SQLite 文件/WAL/Metrics ID 不变;jar 内容除 `org.cubexmc.core.*` 新增类外与接入前等价。
