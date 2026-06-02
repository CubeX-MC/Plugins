# CubeX Config / I18n 可选模块设计稿

本稿只设计 `modules/cubex-config` 与 `modules/cubex-i18n` 的最小可接入 API, 不包含实现计划, 不修改任何插件代码或 Gradle。依据为 `CUBEX_CORE_DESIGN.md` §1.4/§1.5 与 §7 Review 结论, 以及 `ARCHITECTURE_PROPOSAL.md` §8 的"小 core + 可选模块"原则。

## 0. 设计红线

- 只从 8 个插件的实测重复抽取, 不做通用配置框架。
- `cubex-config` 与 `cubex-i18n` 是两个独立可选模块, 均依赖 `cubex-core`。首版建议 `cubex-i18n` 不依赖 `cubex-config`, 通过自己的资源保存/读取入口保持独立; 若后续发现重复过重, 再用 adapter 复用 config 的资源服务。
- 接入目标是零行为变更: 文件路径、默认值合并、reload 顺序、语言目录、消息 key、placeholder 风格、缺失 key fallback、legacy `&` 与 hex `&#RRGGBB` 颜色都必须逐插件保真。
- 不纳入强类型字段读取, 不替换 `ConfigManager` / `ConfigFacade` 的业务 getter。
- 不纳入 EcoBalancer 的 `ConfigMigrator`, Metro/Railway 的 `ConfigUpdater` / `DataFileUpdater`, 以及 RuleGems 现有带备份的 updater, 除非只作为等价 adapter 调用。
- 不纳入业务枚举 label、富文本组件 DSL、MiniMessage/Adventure 组件 DSL。
- Bukkit `YamlConfiguration#save` / `copyDefaults(true)` 会重写 YAML 并丢注释/格式。通用默认键合并只能用于明确接受该副作用的文件; EcoBalancer、Metro、Railway 这类已有迁移器/updater 的插件首批不使用通用合并。

## 1. 实测重复清单

### 1.1 配置保存 / 默认合并 / reload

| 插件 | 实测位置 | 现有行为 | 差异判断 |
| --- | --- | --- | --- |
| BookLite | `BookLitePlugin.java:29-35`, `:69-83`, `:85-87`; `ConfigManager.java:34-60` | enable/reload 保存 `config.yml`, `lang/zh_CN.yml`, `lang/en_US.yml`; `ConfigManager.load()` 调 `saveDefaultConfig()` 后读强类型字段; reload 会在 SQLite 文件名/WAL 改变时重开 repository 并 resize cache。 | 资源保存与 reload 外壳重复度高; 强类型字段和 DB 重开逻辑不抽。 |
| FAWEReplacer | `FAWEReplace.java:41-56`, `:133-139`, `:272-306`, `:333-419` | 保存 `config.yml`; 手动创建/加载 `rules.yml`; reload 调 `reloadConfig()` 并 `languageManager.reload(language)`; 方块/实体规则读取高度业务化。 | 资源保存/YAML 读取可抽; `rules.yml` 解析与任务配置不抽。 |
| MountLicense | `MountLicensePlugin.java:37-43`, `:135-147`; `ConfigManager.java:67-130` | 保存 `config.yml`, `vehicle-profiles.yml`, lang 文件; `ConfigManager.load()` 调 `reloadConfig()` 读强类型字段并保留 legacy display format 兼容。 | 与 BookLite 高相似; 业务字段/legacy fallback 不抽。 |
| Contracts | `ContractPlugin.java:29-32`, `:81-88`, `:111-112`, `:115-137` | 保存 `config.yml` 与 lang 文件; reload 先关闭 GUI session, 再 `reloadConfig()`, `languageManager.load()`, `contractStorage.load()`; 定时任务 interval 从 config 读。 | 默认资源/reload 组合可抽; GUI close、storage reload、任务配置不抽。 |
| EcoBalancer | `EcoBalancer.java:144-159`, `:293-320`, `:1468`; `ConfigMigrator.java:16-19`, `:45`, `:322-367`, `:375-388` | 先运行版本化 `ConfigMigrator`, 再 `saveDefaultConfig()` / `loadConfiguration()`; lang 文件也有版本迁移和备份; reload 还涉及任务取消、file logger、数据库。 | 不能用通用 YAML 合并替代迁移器; 仅可复用资源存在检查、Yaml 读取、Reloadable 编排。 |
| RuleGems | `RuleGems.java:213-216`; `ConfigManager.java:66-68`, `:115`, `:190-218`; `ConfigUpdater.java:22-24`, `:45-68` | `saveDefaultConfig()` + `ConfigUpdater.merge()` + `reloadConfig()`; gems/powers 默认文件复制; updater 会读取默认 YAML、补缺 key、备份后保存。 | 有真实默认键合并重复, 但已有备份语义; 可设计通用 merge API, 试点前需 adapter 保留备份/日志。 |
| Metro | `Metro.java:83-92`, `:275-306`; `ConfigUpdater.java:28-39`; `DataFileUpdater.java:44`, `:106`, `:183`; `ConfigFacade.java:148`, `:639` | 保存 config 后 `ConfigUpdater.applyDefaults()`; `ConfigFacade.reload()` 强类型缓存; `DataFileUpdater.migrateAll()` 迁移线路/站点/传送门数据; reload 会重新生成多个默认数据文件。 | 通用合并首批禁用; updater/data migrator/config facade 保留。 |
| Railway | `Railway/Metro.java:91-104`, `:308-340`; `ConfigUpdater.java:29-69`; `DataFileUpdater.java:44`, `:106`, `:183`; `ConfigFacade.java:150`, `:651` | 与 Metro 同源, 多 `entity.yml` 的默认合并与实体配置; 同样有数据迁移和强类型 facade。 | 与 Metro 一样, 不用通用合并替换 updater。 |

结论:

- 高重复: 保存缺失资源、读取 YAML、把 reload 步骤作为生命周期对象统一调度。
- 中重复: 默认 key 合并。RuleGems/Metro/Railway/EcoBalancer 都有类似逻辑, 但迁移/备份/注释副作用不同, 首版只给 API, 接入时逐插件决定。
- 低重复: 强类型配置字段、业务校验、数据库/任务重排、数据文件 schema 迁移。

### 1.2 i18n 加载 / 取值 / placeholder / fallback / 颜色

| 插件 | 实测位置 | 现有行为 | 差异判断 |
| --- | --- | --- | --- |
| BookLite | `LanguageManager.java:30-37`, `:40-56`, `:59-83`, `:86-87`; resources `lang/zh_CN.yml`, `lang/en_US.yml` | 加载 `lang/<locale>.yml`, 缺文件 fallback 到 `zh_CN`; 缺 key 返回 key; `{prefix}` 替换 prefix, `%name%` 替换 map; `&` legacy 颜色。 | 可用通用 i18n 承接。 |
| FAWEReplacer | `LanguageManager.java:43-83`, `:96-122`, `:128-138` | 缺外部文件时 `saveResource`; 若资源也缺则 fallback `zh_CN`; 缺 key 返回 key; placeholder 是 varargs `{name}`; 没有统一颜色转换。 | 加载/fallback 可抽; placeholder 风格需可配置。 |
| MountLicense | `LanguageManager.java:34-45`, `:48-56`, `:75-83`; `MountLicensePlugin.java:135-147` | 与 BookLite 基本同构, 多 actionbar/list 发送能力; `{prefix}` + `%name%`; 缺 key 返回 key; legacy `&`。 | 可作为第二个小试点; actionbar/list 是 facade 方法, 不进 core i18n 基础接口。 |
| Contracts | `LanguageManager.java:24-41`, `:48-74`, `:77-83`; `Text.java:14-24` | config `language` 经过 sanitize; 文件缺失 fallback `zh_CN`; message key 自动拼 `messages.` 前缀; `%prefix%` + `%name%`; 缺 key 返回 path; 支持 `&` 与 `&#RRGGBB`; status/type/role/condition/term 是业务 label。 | 通用 i18n 可覆盖普通消息; key prefix 与 enum label 通过 adapter 保留。 |
| EcoBalancer | `EcoBalancer.java:293-320`, `:323-329`; `MessageUtils.java:28-41`, `:55-106`; `ConfigMigrator.java:322-367` | `config.language` 默认 `en_US`; 加载 `lang/<lang>.yml`; prefix 来自 lang; `%name%`; legacy `&`; 另有 clickable TextComponent 组装; lang 文件版本迁移。 | 普通 String 消息可抽; clickable 组件 DSL 和 lang migrator 不抽。 |
| RuleGems | `LanguageManager.java:67-85`, `:109-120`, `:122-201`, `:239-270`, `:297-298`; `ColorUtils.java:19-37` | `updateBundledLanguages()` 合并 bundled lang; 默认/回退语言 `zh_CN` 与 `en_US`; 缺 key warn 后返回 path; list 支持 fallback; `%prefix%` + `%name%`; 支持 `&` 与 `&#RRGGBB`; title 发送含 scheduler。 | 可设计 fallback 链/list/hex; title 发送和 scheduler 不进 i18n 基础接口。 |
| Metro | `LanguageManager.java:39-45`, `:63-82`, `:101-104`, `:125-141`, `:151-170`, `:181-194`; `ColorUtil.java:14-38` | 加载所有 `lang/*.yml`; `settings.default_language` 同时作为 default/current; 缺 key 返回 `"Missing message: " + key`; 支持 `%1` 位置参数与 `{name}` 命名参数; `LanguageUpdater.merge`; 支持 `&`/hex。 | 支持多语言缓存、missing policy、两种 placeholder 风格; updater 不直接替换。 |
| Railway | `LanguageManager.java:39-45`, `:63-82`, `:101-104`, `:125-141`, `:151-170`, `:181-194`; `ColorUtil.java:14-38` | 与 Metro 同源; 资源含 `de_DE/en_US/es_ES/nl_NL/tr_TR/zh_CN/zh_TW`; 支持同样 fallback/missing/placeholder/color。 | 与 Metro 共用 adapter 最有价值。 |

资源布局证据: 8 个插件均有 `src/main/resources/lang/*.yml`; Metro/Railway 含 7 种语言, 其余插件多为 `zh_CN/en_US`。配置资源除 `config.yml` 外还包括 FAWEReplacer `rules.yml`, MountLicense `vehicle-profiles.yml`, Metro/Railway `lines.yml`/`stops.yml`, Railway `entity.yml`, RuleGems `features/*.yml`/`gems/*.yml`/`powers/*.yml`。

结论:

- 高重复: `lang/<locale>.yml` 文件加载、默认/回退 locale、缺 key policy、legacy/hex 颜色。
- 中重复: placeholder。BookLite/MountLicense 用 `{prefix}` + `%name%`; Contracts/EcoBalancer/RuleGems 用 `%prefix%` + `%name%`; FAWEReplacer 用 `{name}`; Metro/Railway 同时有 `%1` 与 `{name}`。
- 低重复: clickable TextComponent、title/actionbar 发送、业务 enum label、LanguageUpdater/Migrator。

## 2. 模块边界

### 2.1 `cubex-config`

职责:

- 保存缺失资源: 单个或多个 classpath resource 到插件 data folder, 不覆盖已有文件。
- 加载 `YamlConfiguration` 或 `FileConfiguration`, 支持 UTF-8 classpath 默认 YAML。
- 提供默认 key 合并 API, 可配置是否备份、是否保存、是否只补缺叶子节点。该 API 必须在文档中标注"会丢注释/格式"。
- 提供 `Reloadable` / `ReloadChain` 编排, 让插件把 `reloadConfig()`, `ConfigManager.load()`, `LanguageManager.load()`, storage reload 等步骤按原顺序注册并调用。
- 绑定 `CubexPlugin` 生命周期: 若某个 reloadable 持有资源, 可通过 `plugin.bind(...)` 关闭; config 模块自身不管理数据库/任务。

不纳入:

- 强类型配置字段和业务 getter。
- 材质/枚举/权限前缀/区块速度/线路参数等业务解析。
- EcoBalancer `ConfigMigrator`、Metro/Railway `ConfigUpdater`/`DataFileUpdater`、RuleGems updater 的备份策略替换。
- 注释保留型 YAML 更新器。首版不承诺保留注释。

### 2.2 `cubex-i18n`

职责:

- 加载 `lang/<locale>.yml`, 支持单语言或多语言缓存。
- 支持 current/default/fallback locale, 并可配置 fallback 链。
- 支持缺 key policy: 返回 key、返回 path、返回 `"Missing message: key"`、返回空串、记录 warning。
- 支持 String 与 List<String> 消息。
- 支持 placeholder 风格: `%name%`, `{name}`, `%1` 位置参数; prefix token 可配置为 `{prefix}` 或 `%prefix%`。
- 使用 `CubexText.color` / `colorOrNull` 做颜色, 支持 legacy `&` 和 hex `&#RRGGBB`。
- 提供轻量 `I18nService` 与 per-plugin adapter 友好的 API; 可选择实现 `Reloadable`。

不纳入:

- MiniMessage/Adventure DSL。
- EcoBalancer clickable TextComponent 拼装。
- RuleGems title/actionbar 发送和 scheduler。
- Contracts status/type/role/condition 等业务 label 方法。
- Metro/Railway 的 `LanguageUpdater.merge` 替换。首批最多用 adapter 调用旧 updater。

### 2.3 模块依赖关系

- `modules/cubex-config` -> `modules/cubex-core`.
- `modules/cubex-i18n` -> `modules/cubex-core`.
- `cubex-i18n` 首版不依赖 `cubex-config`, 以避免小插件只想用 i18n 时被迫引入 config。两边会各自暴露最小资源保存入口; 若后续实现中重复超过收益阈值, 再引入 `cubex-config` 的 optional adapter, 但不是首版 API 前提。

## 3. Java API 契约草案

以下只给签名和职责, 不是实现代码。

### 3.1 共享 `cubex-core` 契约

为了让 `cubex-config` 与 `cubex-i18n` 都能实现 reload 契约而不互相依赖, 建议在后续实现时向 `cubex-core` 增加一个极小接口:

```java
package org.cubexmc.core;

public interface Reloadable {
    void reload() throws Exception;
}
```

这是生命周期契约, 不引入第三方依赖, 也不携带配置/i18n 语义。

### 3.2 `cubex-config`

```java
package org.cubexmc.config;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.core.Reloadable;

public final class ReloadChain implements Reloadable {
    public static ReloadChain create();
    public ReloadChain add(String name, Reloadable reloadable);
    public ReloadChain add(String name, Runnable action);
    public List<String> names();
    @Override public void reload() throws Exception;
}

public final class ResourceFiles {
    public ResourceFiles(CubexPlugin plugin);
    public boolean saveIfMissing(String resourcePath);
    public List<String> saveIfMissing(Collection<String> resourcePaths);
    public File dataFile(String resourcePath);
    public boolean exists(String resourcePath);
}

public final class YamlFiles {
    public YamlFiles(CubexPlugin plugin);
    public YamlConfiguration loadDataFile(String resourcePath);
    public YamlConfiguration loadDataFile(File file);
    public YamlConfiguration loadResource(String resourcePath, Charset charset);
    public YamlConfiguration loadResourceUtf8(String resourcePath);
}

public final class DefaultMergeOptions {
    public static DefaultMergeOptions copyMissingKeys();
    public DefaultMergeOptions backupBeforeSave(boolean enabled);
    public DefaultMergeOptions saveWhenChanged(boolean enabled);
    public DefaultMergeOptions includeSections(boolean enabled);
    public DefaultMergeOptions warnAboutCommentLoss(boolean enabled);
}

public final class DefaultMergeResult {
    public boolean changed();
    public List<String> addedKeys();
    public File backupFile();
}

public final class YamlDefaults {
    public YamlDefaults(CubexPlugin plugin);
    public DefaultMergeResult mergeResourceIntoDataFile(
            String resourcePath,
            DefaultMergeOptions options);
    public DefaultMergeResult mergeResourceIntoDataFile(
            String resourcePath,
            File targetFile,
            DefaultMergeOptions options);
}

public final class ConfigReload {
    public static Reloadable bukkitConfig(CubexPlugin plugin);
    public static Reloadable fromRunnable(String name, Runnable action);
    public static Reloadable fromThrowing(String name, Reloadable reloadable);
}
```

职责说明:

- `ResourceFiles` 承接 `saveResourcesIfMissing(...)` / `saveResource(..., false)` 的重复。
- `YamlFiles` 只负责读 YAML, 不做业务解析。
- `YamlDefaults` 只补缺 key, 不承诺注释保留。调用方必须在接入验收里证明 plugin.yml、默认资源和 reload 行为等价。
- `ReloadChain` 只负责按原顺序调用 reload 步骤, 不自动推断任何插件逻辑。

### 3.3 `cubex-i18n`

```java
package org.cubexmc.i18n;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.core.Reloadable;

public enum MissingKeyMode {
    RETURN_KEY,
    RETURN_PATH,
    RETURN_EMPTY,
    RETURN_MISSING_MESSAGE_PREFIX
}

public enum PlaceholderStyle {
    PERCENT_NAME,
    BRACE_NAME,
    POSITIONAL_PERCENT_INDEX
}

public final class I18nOptions {
    public static I18nOptions create();
    public I18nOptions languageDirectory(String directory);
    public I18nOptions currentLocale(String locale);
    public I18nOptions currentLocale(Supplier<String> localeSupplier);
    public I18nOptions defaultLocale(String locale);
    public I18nOptions fallbackLocales(List<String> locales);
    public I18nOptions bundledLocales(Collection<String> locales);
    public I18nOptions prefixKey(String key);
    public I18nOptions prefixToken(String token);
    public I18nOptions keyPrefix(String keyPrefix);
    public I18nOptions missingKeyMode(MissingKeyMode mode);
    public I18nOptions warnOnMissingKey(boolean enabled);
    public I18nOptions placeholderStyles(Collection<PlaceholderStyle> styles);
    public I18nOptions colorize(boolean enabled);
}

public interface I18nService extends Reloadable {
    String currentLocale();
    void setCurrentLocale(String locale);
    void reload();
    String raw(String key);
    String raw(String key, String locale);
    List<String> rawList(String key);
    List<String> rawList(String key, String locale);
    String message(String key);
    String message(String key, Map<String, ?> placeholders);
    String message(String key, String locale, Map<String, ?> placeholders);
    String message(String key, Object... positionalArgs);
    List<String> messageList(String key, Map<String, ?> placeholders);
    void send(CommandSender sender, String key, Map<String, ?> placeholders);
}

public final class I18nServices {
    public static I18nService create(CubexPlugin plugin, I18nOptions options);
}

public final class Placeholders {
    public static Map<String, Object> empty();
    public static Map<String, Object> of(String key, Object value);
    public static Map<String, Object> put(Map<String, Object> args, String key, Object value);
}
```

职责说明:

- `I18nOptions.keyPrefix("messages.")` 承接 Contracts 的 `messages.` 自动前缀; 默认为空。
- `prefixToken("{prefix}")` 或 `prefixToken("%prefix%")` 承接 BookLite/MountLicense 与 Contracts/EcoBalancer/RuleGems 的差异。
- `placeholderStyles(...)` 允许同一服务同时支持 `%name%` 与 `{name}` 或 `%1`。
- `MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX` 承接 Metro/Railway; `RETURN_KEY` / `RETURN_PATH` 承接 BookLite/FAWEReplacer/Contracts/RuleGems。
- `CubexText` 是唯一颜色入口, 避免每个插件继续复制 `Text` / `ColorUtil` / `ColorUtils` 的 hex 正则。

## 4. 试点选择与映射

建议第一个试点选择 BookLite。

理由:

- BookLite 已完成 `cubex-core` 最小接入, 主类较小。
- 配置行为清晰: `saveDefaultResources()` 只涉及 `config.yml` 和两个 lang 文件, `ConfigManager.load()` 仍保留强类型读取。
- i18n 行为明确: `lang/<locale>.yml`, fallback `zh_CN`, 缺 key 返回 key, `{prefix}` + `%name%`, legacy `&`。
- reload 有真实副作用(SQLite 文件名/WAL 改变时重开 repository), 能验证 `ReloadChain` 不会吞掉业务步骤。

接入前伪代码:

```java
protected void enablePlugin() {
    saveDefaultResources();
    configManager = new ConfigManager(this);
    configManager.load();
    languageManager = new LanguageManager(this, configManager.getLanguage());
    languageManager.load();
}

public void reloadAll() {
    String oldSqliteFile = configManager.getSqliteFile();
    boolean oldWal = configManager.isWal();
    saveDefaultResources();
    reloadConfig();
    configManager.load();
    languageManager.setLocale(configManager.getLanguage());
    languageManager.load();
    if (storageChanged(oldSqliteFile, oldWal)) {
        repository.close();
        repository.init();
        cache.clear();
    }
    cache.resize(...);
}
```

接入后伪代码:

```java
private ResourceFiles resources;
private I18nService i18n;

protected void enablePlugin() {
    resources = new ResourceFiles(this);
    resources.saveIfMissing(List.of("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"));

    configManager = new ConfigManager(this);
    configManager.load();

    i18n = I18nServices.create(this, I18nOptions.create()
            .languageDirectory("lang")
            .currentLocale(configManager::getLanguage)
            .defaultLocale("zh_CN")
            .fallbackLocales(List.of("zh_CN"))
            .bundledLocales(List.of("zh_CN", "en_US"))
            .prefixKey("prefix")
            .prefixToken("{prefix}")
            .missingKeyMode(MissingKeyMode.RETURN_KEY)
            .placeholderStyles(List.of(PlaceholderStyle.PERCENT_NAME))
            .colorize(true));
    i18n.reload();

    languageManager = new BookLiteLanguageAdapter(i18n); // 保留原 public surface
}

public void reloadAll() {
    String oldSqliteFile = configManager.getSqliteFile();
    boolean oldWal = configManager.isWal();
    resources.saveIfMissing(List.of("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"));
    reloadConfig();
    configManager.load();
    i18n.reload();
    if (storageChanged(oldSqliteFile, oldWal)) {
        repository.close();
        repository.init();
        cache.clear();
    }
    cache.resize(...);
}
```

行为不变论证:

- 文件路径不变: `config.yml`, `lang/zh_CN.yml`, `lang/en_US.yml`。
- 不启用通用 YAML merge, 因此不会重写 BookLite 的 YAML 或改变注释/格式。
- `ConfigManager` 原类保留, 所有强类型默认值与 clamp 逻辑不变。
- 缺语言文件仍 fallback 到 `zh_CN`; 缺 key 仍返回 key。
- `{prefix}` 和 `%name%` 的替换顺序保持为先 prefix、后 placeholder、最后 color。
- SQLite/WAL reload 副作用仍在 BookLite 自己的 `reloadAll()` 中处理。

推广顺序建议:

1. BookLite: 验证最小 i18n/config API 与 core 生命周期。
2. MountLicense: 与 BookLite 近似, 但增加 list/actionbar adapter。
3. Contracts: 验证 `keyPrefix("messages.")`, `%prefix%`, hex 颜色, enum label 保留。
4. FAWEReplacer: 验证 `{name}` varargs adapter 与 `rules.yml` 非通用解析保留。
5. RuleGems: 只在 adapter 中等价调用旧 `ConfigUpdater` / `LanguageUpdater`, 再逐步评估通用 merge。
6. EcoBalancer: 仅抽普通 String i18n, 保留 clickable DSL 与 ConfigMigrator。
7. Metro/Railway: 最后处理, 重点保留 `LanguageUpdater`, `ConfigUpdater`, `DataFileUpdater`, `ConfigFacade`。

## 5. 风险与取舍

- 注释丢失: `YamlConfiguration#save` 和 `copyDefaults(true)` 会重写文件, 通用 merge 首批不得用于 EcoBalancer、Metro、Railway; RuleGems 也必须保留备份/日志语义后才能考虑。
- placeholder 兼容: 不能把 `%name%`, `{name}`, `%1` 统一改写成单一风格。API 只提供可配置策略, 各插件 adapter 保留原 public surface。
- 缺 key fallback: BookLite/FAWEReplacer/Contracts/RuleGems 多数返回 key/path, Metro/Railway 返回 `"Missing message: key"`; 这是玩家/控制台可观察行为, 必须配置化。
- 颜色差异: BookLite/MountLicense/EcoBalancer 只实测 legacy `&`; Contracts/RuleGems/Metro/Railway 实测 hex `&#RRGGBB`。统一入口可以支持两者, 但不能引入 MiniMessage 解释。
- 迁移器边界: EcoBalancer 的版本化配置/lang 迁移和 Metro/Railway 的数据 schema 迁移都不是"默认值补缺", 属于业务迁移, 保留各插件。
- adapter 成本: 为零行为变更, 初期需要 per-plugin adapter 包住 `LanguageManager`/`ConfigManager` 的现有方法; 直接替换调用点会扩大风险。
- i18n/config 依赖关系: i18n 不依赖 config 会有少量资源保存重复, 但换来按需引入和较小 blast radius; 等第二批接入后再评估是否值得抽共享 internal。

## 6. Review 关注点

- 是否接受 `cubex-i18n` 首版不依赖 `cubex-config`。
- 是否接受向 `cubex-core` 增加极小 `Reloadable` 接口, 让 config/i18n 保持独立可选模块。
- 是否同意 BookLite 作为第一个 config/i18n 试点。
- 是否同意通用 YAML merge 首批只作为 API 存在, 不替换 EcoBalancer/Metro/Railway 既有 updater/migrator。

## 7. Review 结论与已定方向(以此为准)

**设计稿作为"行为保真去重"的基础通过。** §6 四问均确认:cubex-i18n 首版不依赖 cubex-config ✓;core 加极小 `Reloadable` ✓;BookLite 首个试点 ✓;通用 YAML merge 首批只作 API、不替换 migrator ✓。

但用户追加诉求:**尽量统一颜色(MiniMessage)、placeholder、配置/数据格式更新控制**。这与"零行为变更"有张力——统一是**内容/行为变更**(要重写 lang/config、迁移线上文件),不是透明重构。已定**两阶段**推进:

### 7.1 两阶段模型
- **阶段 A(现在,零行为变更去重)**:按本设计接入 cubex-config/i18n,用 **legacy `&`/hex + 各插件原 placeholder 风格**(兼容模式),纯去重。不切 MiniMessage、不统一 placeholder、不动各插件 migrator。
- **阶段 B(逐插件现代化,行为/内容变更)**:把某插件切到 **MiniMessage + 统一 placeholder + 统一版本化迁移框架**,**同时重写该插件 lang/config 并 bump 版本号让线上文件自动迁移**,单独验收。一次一个。

### 7.2 模块现在就设计成双模式(避免阶段 B 返工)
- **颜色 `ColorMode { LEGACY_AND_HEX, MINIMESSAGE }`**(在 `cubex-i18n`)。**MiniMessage 渲染放 i18n、不放 core**(core 保持零第三方依赖;MiniMessage=net.kyori adventure,在 i18n shade+relocate)。阶段 A 默认 `LEGACY_AND_HEX`(用 core `CubexText`);`MINIMESSAGE` 的实现在阶段 B 首次现代化时落地(API 现在预留,实现可延后,避免 YAGNI)。RuleGems/Metro/Railway 本就带 adventure,代价低;BookLite/MountLicense/Contracts/EcoBalancer/FAWEReplacer 现代化时需按插件评估打包 adventure(Spigot 目标需 shade+relocate)。
- **placeholder**:阶段 A 保留可配置风格(`%name%`/`{name}`/`%1`);阶段 B 指定一个**现代化默认风格**(MiniMessage tag-resolver 友好,如 `<name>`)。`PlaceholderStyle` 枚举预留该值。
- **配置/数据更新控制 = 统一机制**(用户已选):在 `cubex-config` 设计**统一版本化迁移框架**(读版本键→按序执行迁移步骤→备份→保存),**各插件提供自己的迁移步骤内容**(机制统一、bespoke 保留)。API 现在在文档锁定;EcoBalancer/Metro/Railway/RuleGems 的 ConfigUpdater/ConfigMigrator/DataFileUpdater 在**阶段 B 逐个 port** 到该框架。阶段 A 不动这些 migrator。

### 7.3 阶段 A 实现范围(下一轮)
只实现并接入(BookLite 试点,零行为变更):
- `cubex-core`:加 `Reloadable`。
- `cubex-config`:`ResourceFiles`、`YamlFiles`、`ReloadChain`、`YamlDefaults`(通用 merge API,仅文档标注丢注释,BookLite 不启用)。**不实现迁移框架(阶段 B 首次 port 时落地)**。
- `cubex-i18n`:`I18nService` + `I18nOptions`,`ColorMode` 枚举(实现 `LEGACY_AND_HEX`,`MINIMESSAGE` 预留)、可配置 placeholder/missing-key/fallback;颜色走 core `CubexText`。
- BookLite:`ResourceFiles` + `I18nService`(兼容模式)接入,保留 `ConfigManager` 与 `BookLiteLanguageAdapter` 包原 surface,零行为变更验收(plugin.yml/lang 内容/缺 key/placeholder/颜色/SQLite reload 不变)。
