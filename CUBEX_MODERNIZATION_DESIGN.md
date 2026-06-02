# CubeX Phase B Modernization Design

本稿只设计阶段 B, 不实现、不修改插件代码或 Gradle。权威前提来自 `CUBEX_CONFIG_I18N_DESIGN.md` §7: 阶段 A 保留 legacy 行为; 阶段 B 才逐插件切换到 MiniMessage、canonical placeholder 与统一版本化迁移框架。BookLite 是第一个现代化试点。

参考事实:

- PaperMC Adventure Bukkit 平台文档当前给出 `net.kyori:adventure-platform-bukkit:4.4.1`, 并说明目标覆盖 Bukkit/Spigot/Paper 1.7.10 到 1.21.11; 同页也明确提示该 Bukkit/Spigot 平台库不再维护, 推荐现代服务端原生 Adventure。来源: https://docs.papermc.io/adventure/platform/bukkit/
- MiniMessage 官方 dynamic replacements 使用 `TagResolver` / `Placeholder` 把 `<name>` 这类 tag 替换为动态文本或 Component, 且 `Placeholder.unparsed` 可安全插入玩家输入。来源: https://docs.papermc.io/adventure/minimessage/dynamic-replacements/
- 本仓库现状: RuleGems 已用 Adventure 4.15.0; Metro/Railway 已用 Adventure BOM 4.25.0; BookLite 当前 lang 是 legacy `&` + `{prefix}` + `%name%`。

## 1. MiniMessage 落地方案

### 1.1 依赖选择

阶段 B 首次落地时, `cubex-i18n` 增加 Adventure/MiniMessage 依赖, `cubex-core` 继续保持零第三方依赖。

建议依赖:

```kotlin
dependencies {
    api(project(":modules:cubex-core"))

    implementation(platform("net.kyori:adventure-bom:4.25.0"))
    implementation("net.kyori:adventure-api")
    implementation("net.kyori:adventure-text-minimessage")
    implementation("net.kyori:adventure-text-serializer-legacy")
    implementation("net.kyori:adventure-text-serializer-plain")

    // Bukkit/Spigot 1.16~1.21 发送兼容层; 注意官方已标注不再维护。
    implementation("net.kyori:adventure-platform-bukkit:4.4.1")
}
```

取舍:

- `adventure-bom:4.25.0` 与 Metro/Railway 现状一致, 避免同仓多套 adventure-api/minimessage 版本。
- `adventure-platform-bukkit:4.4.1` 是官方文档当前列出的 Bukkit platform 版本; 由于官方标注该平台库不再维护, 它只能作为 Spigot 兼容桥, 不能承诺长期演进能力。
- 如果后续发现 `adventure-platform-bukkit:4.4.1` 对某个目标 Spigot patch 不稳定, 回退策略是保留 `LegacyComponentSerializer` 序列化发送路径。

### 1.2 打包与 relocate

`cubex-i18n` 不自己产出 shaded jar; 它作为模块依赖被各插件 shadow 进最终插件 jar。阶段 B 每个插件在自己的 `shadowJar` 中处理 Adventure:

```kotlin
tasks.shadowJar {
    relocate("net.kyori", "org.cubexmc.<plugin>.libs.kyori")
    relocate("net.kyori.adventure", "org.cubexmc.<plugin>.libs.kyori.adventure")
    // 实际写法只需 relocate("net.kyori", "...") 一条即可覆盖子包。
}
```

规则:

- 不 relocate `org.cubexmc.*`。
- 不 minimize Adventure/MiniMessage/platform-bukkit, 因为 platform 层和 serializers 有反射/运行时服务发现风险。
- 每个插件独立 relocate 到 `org.cubexmc.<plugin>.libs.kyori`, 避免 BookLite 与 RuleGems/Metro/Railway 运行在同服时共享未对齐的 kyori classes。
- RuleGems/Metro/Railway 已有 Adventure 依赖; 现代化它们时要把既有 Adventure relocate 规则与 `cubex-i18n` 的依赖合并, 避免同一插件 jar 内出现 relocated 与 unrelocated 双份。

### 1.3 Component vs legacy String 发送

`cubex-i18n` 增加双出口:

- Component 出口: `component(key, placeholders)` 返回 Adventure `Component`。
- 发送出口: `send(sender, key, placeholders)` 用 `BukkitAudiences` 发送 Component。
- legacy 出口: `message(key, placeholders)` 返回 `LegacyComponentSerializer.legacySection().serialize(component)`。

推荐发送路径:

1. 玩家/命令发送: 优先 `BukkitAudiences.sender(sender).sendMessage(component)`。官方文档说明 `BukkitAudiences` 负责跨 Bukkit/Spigot/Paper 版本的兼容发送。
2. 控制台发送: 也走 `BukkitAudiences.sender(console)`; 若初始化失败或处于 disable 后, fallback 到 legacy section string。
3. 物品名、书页、scoreboard、BossBar、Title 等无法统一走 `Audience` 的旧 Bukkit API: 用 legacy section string, 保持与旧 API 兼容。
4. `BukkitAudiences` 必须绑定生命周期: `plugin.bind(adventure::close)`。禁用后访问应抛清晰异常或 fallback legacy, 不能静默 NPE。

### 1.4 `ColorMode.MINIMESSAGE` 语义

阶段 A 的 `LEGACY_AND_HEX` 保持返回 legacy String。阶段 B 的 `MINIMESSAGE` 定义为:

- `raw(key)` 返回未渲染 MiniMessage 模板字符串。
- `component(key, placeholders)` parse MiniMessage 并应用 TagResolver。
- `message(key, placeholders)` 把 Component serialize 为 legacy section string, 只用于旧 Bukkit API surface。
- `send(...)` 直接发送 Component。
- MiniMessage parse 默认使用非 strict 模式, 但迁移后的 bundled lang 必须通过 strict-ish 单测: 所有 `<...>` 中未注册且不属于 MiniMessage 标准 tag 的内容都应被转义。

## 2. Canonical Placeholder

### 2.1 统一格式

阶段 B 统一 placeholder 为 MiniMessage tag:

```text
<name>
```

理由:

- 与 MiniMessage / TagResolver 原生模型一致, 不需要先做字符串替换再 parse。
- 可以区分不同安全级别: `Placeholder.unparsed("name", value)` 用于玩家输入; `Placeholder.component("icon", component)` 用于插件生成的可信 Component。
- 未来支持 hover/click/style formatter 时无需再换语法。

### 2.2 `PlaceholderStyle.MINIMESSAGE_TAG`

`PlaceholderStyle.MINIMESSAGE_TAG` 的语义:

- 模板中的 `<name>` 是占位符, 由 `TagResolver` 解析。
- 默认对所有外部输入使用 `Placeholder.unparsed(name, value)`, 防止玩家输入 `<red>` 之类内容被二次解析。
- 对插件内部可信文本可显式使用 parsed/component placeholder:
  - `placeholder("name", value)` -> unparsed
  - `parsedPlaceholder("name", miniMessage)` -> parsed
  - `componentPlaceholder("name", component)` -> component
- placeholder 名称限制为 `[a-z0-9_:-]+`; 旧 `%short_id%` 转为 `<short_id>`, 旧 `{prefix}` 转为 `<prefix>`。

### 2.3 对 lang 文件的影响

BookLite 旧格式:

```yaml
prefix: "&8[&bBookLite&8] &r"
commands:
  unknown: "{prefix}&c未知子命令：&f%input%"
  help_info: "&e/booklite info <id> &7- 查看单本书详情（支持短 ID）"
```

BookLite 新格式:

```yaml
lang-version: 2
prefix: "<dark_gray>[<aqua>BookLite<dark_gray>] <reset>"
commands:
  unknown: "<prefix><red>未知子命令：<white><input>"
  help_info: "<yellow>/booklite info \\<id\\> <gray>- 查看单本书详情（支持短 ID）"
```

注意:

- 旧 `%input%` 是 placeholder, 迁移为 `<input>`。
- 旧命令用法里的 `<id>` 是字面文本, 必须转义为 `\\<id\\>`, 不能误判为 placeholder。
- `prefix` 从普通字符串变为 MiniMessage 模板; 消息内仍用 `<prefix>` 占位符插入。

## 3. 版本化迁移框架

这是阶段 B 的关键风险点。`cubex-config` 首次落地统一迁移机制, 但每个插件仍提供自己的迁移步骤内容。

### 3.1 API 草案

```java
package org.cubexmc.config.migration;

import java.io.File;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;

public final class MigrationPlan {
    public static MigrationPlan yaml(String name, String resourcePath);
    public MigrationPlan versionKey(String key);
    public MigrationPlan targetVersion(int version);
    public MigrationPlan backupDirectory(String relativePath);
    public MigrationPlan restoreBackupOnSaveFailure(boolean enabled);
    public MigrationPlan addStep(MigrationStep step);
}

public interface MigrationStep {
    int fromVersion();
    int toVersion();
    String description();
    void migrate(MigrationContext context) throws Exception;
}

public interface MigrationContext {
    CubexPlugin plugin();
    File targetFile();
    YamlConfiguration yaml();
    YamlConfiguration defaults();
    void markChanged();
    boolean changed();
    void set(String path, Object value);
    String getString(String path);
    List<String> leafStringPaths();
}

public final class MigrationRunner {
    public MigrationRunner(CubexPlugin plugin);
    public MigrationReport run(MigrationPlan plan);
}

public final class MigrationReport {
    public boolean migrated();
    public int fromVersion();
    public int toVersion();
    public File backupFile();
    public List<String> appliedSteps();
    public List<String> warnings();
}
```

执行流程:

1. 解析目标文件, 若不存在则 `saveResource(resourcePath, false)` 后视为 target version。
2. 读取 `versionKey`, 缺失视为版本 `1`。BookLite 旧文件没有版本键, 所以旧 config/lang 都从 1 开始。
3. 如果当前版本高于代码 target version, 记录 warning, 不降级、不写文件。
4. 按 `fromVersion -> toVersion` 连续查找步骤; 缺步骤立即失败, 不写文件。
5. 在第一次写入前创建备份, 路径建议:
   - `backups/migrations/<yyyyMMdd-HHmmss>/<resourcePath>`
6. 所有步骤在内存 YAML 上执行, 最后设置 versionKey 到 target version。
7. 保存到临时文件, 再替换原文件。若保存或替换失败, 根据 `restoreBackupOnSaveFailure` 尝试恢复备份。
8. report 写日志: from/to、backup path、applied steps、warnings。

### 3.2 迁移失败策略

迁移失败不能静默吞掉:

- 默认策略: 保留原文件, 记录 SEVERE, abort enable, 提示备份/原文件路径。
- 可选策略: 对纯 i18n 文件允许 fallback 到 `LEGACY_AND_HEX` 兼容模式继续启用, 但必须在日志中标明"未完成现代化迁移"。
- BookLite 试点建议用严格策略: lang/config 迁移失败就 abort enable。原因是 BookLite 文件小, 迁移规则可测试; 严格能尽早暴露线上不兼容。

### 3.3 legacy `&` / `&#hex` -> MiniMessage 迁移步骤

迁移步骤名: `LegacyTextToMiniMessageStep`.

适用范围:

- BookLite `lang/*.yml` 的所有 string leaf。
- BookLite `config.yml` 中如果未来出现显示文本字段才处理; 当前 BookLite config 没有用户可见文本, 只 bump version。

核心算法:

1. 遍历 YAML string leaf。
2. 对每个字符串做 tokenization, 不直接正则全局替换:
   - 识别 legacy code: `&0-9a-fk-or`。
   - 识别 hex code: `&#RRGGBB`。
   - 识别旧 placeholder:
     - `{prefix}` -> `<prefix>`
     - `%[A-Za-z0-9_:-]+%` -> `<name>`
   - 其它文本先 escape MiniMessage tags, 避免 `<id>` 这类字面 usage 被解析。
3. legacy 颜色映射:
   - `&0` -> `<black>`, `&1` -> `<dark_blue>`, ..., `&f` -> `<white>`
   - `&l` -> `<bold>`, `&o` -> `<italic>`, `&n` -> `<underlined>`, `&m` -> `<strikethrough>`, `&k` -> `<obfuscated>`
   - `&r` -> `<reset>`
   - `&#55AAFF` -> `<#55AAFF>`
4. 不自动生成闭合 tag。旧 legacy 样式本来就是状态机, MiniMessage 的连续 style tag 能表达同样视觉状态。
5. 如果发现无法安全转换的片段, 在 report warnings 中记录 path 和原因, 并保持该 leaf 原值或 abort, 由 plan 决定。BookLite 试点建议 abort, 不保留半迁移。

特殊处理:

- `%title%`、`%short_id%`、`%actual%` 等确定是 placeholder, 迁移为 `<title>`、`<short_id>`、`<actual>`。
- 命令帮助文本里的 `<id>`、`<days>` 是字面 usage, 迁移为 `\\<id\\>`、`\\<days\\>`。
- 如果用户自定义 lang 已经包含 MiniMessage tag, 由于阶段 B 从 legacy 版本迁移, 默认会 escape 它, 避免误执行未知自定义 tag。用户要使用 MiniMessage 需迁移后再编辑。

### 3.4 备份与回退

备份策略:

- 每个被迁移文件写入前先备份。
- 备份目录保存原相对路径, 例如:
  - `backups/migrations/20260602-143000/config.yml`
  - `backups/migrations/20260602-143000/lang/zh_CN.yml`
  - `backups/migrations/20260602-143000/lang/en_US.yml`
- report 中输出所有备份路径。

回退策略:

- 自动回退只处理"保存失败/替换失败"这种机械错误。
- 视觉不满意、语义不满意属于人工回退: 管理员停服, 从 backup 还原文件, 将插件版本降回阶段 A 或把 `color-mode` 临时设为 legacy 兼容模式。
- 不提供无备份迁移选项。

## 4. BookLite 现代化映射

### 4.1 文件版本 bump

BookLite 阶段 B 目标:

```yaml
# config.yml
config-version: 2
language: zh_CN
...
```

```yaml
# lang/zh_CN.yml, lang/en_US.yml
lang-version: 2
prefix: "<dark_gray>[<aqua>BookLite<dark_gray>] <reset>"
...
```

迁移触发:

1. `enablePlugin()` 先保存缺失资源。
2. `MigrationRunner` 跑 `config.yml` 计划。
3. `MigrationRunner` 跑所有 bundled lang 计划, 至少 `zh_CN` / `en_US`。
4. `reloadConfig()`, `ConfigManager.load()`, `I18nService.reload()`。
5. 如果任一迁移失败, BookLite abort enable, 日志给出备份路径/失败 path。

### 4.2 bundled lang 重写示例

旧:

```yaml
prefix: "&8[&bBookLite&8] &r"
commands:
  unknown: "{prefix}&c未知子命令：&f%input%"
  help_info: "&e/booklite info <id> &7- 查看单本书详情（支持短 ID）"
book:
  fail_too_many_pages: "{prefix}&c书页过多：&e%actual%&c / 上限 &e%max%&c。"
admin:
  list_line: "&8- &f#%short_id% &7| &f%title% &7| 作者 &f%author% &7| 页数 &f%pages%"
```

新:

```yaml
lang-version: 2
prefix: "<dark_gray>[<aqua>BookLite<dark_gray>] <reset>"
commands:
  unknown: "<prefix><red>未知子命令：<white><input>"
  help_info: "<yellow>/booklite info \\<id\\> <gray>- 查看单本书详情（支持短 ID）"
book:
  fail_too_many_pages: "<prefix><red>书页过多：<yellow><actual><red> / 上限 <yellow><max><red>。"
admin:
  list_line: "<dark_gray>- <white>#<short_id> <gray>| <white><title> <gray>| 作者 <white><author> <gray>| 页数 <white><pages>"
```

BookLite `I18nOptions` 阶段 B:

```java
I18nOptions.create()
    .colorMode(ColorMode.MINIMESSAGE)
    .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG))
    .prefixKey("prefix")
    .prefixToken("<prefix>")
    .missingKeyMode(MissingKeyMode.RETURN_KEY)
```

### 4.3 调用面迁移

BookLite 阶段 B 可以分两层做:

- 第一层: 保留 `LanguageManager` adapter surface, 但 `send(...)` 走 Component; `msg(...)` 继续返回 legacy string 供旧 Bukkit API 使用。
- 第二层: 对命令/聊天路径逐步从 `sender.sendMessage(lang.msg(...))` 改成 `lang.send(...)`; 对 item/book/title 等必须 String 的路径继续用 legacy serializer。

BookLite 首次现代化建议只做第一层加少量关键调用点:

- `BookLiteCommand` 中直接 `sender.sendMessage(lang.msg(...))` 的帮助/详情路径可保留 legacy string, 因为视觉等价即可。
- `lang.send(sender, ...)` 由 adapter 内部升级为 Component 发送。
- `BookCodec` 里用于书本缺失标题/页面的 `lang.msg(...)` 保持 legacy string, 避免引入 Adventure book component 改造。

### 4.4 视觉等价验收

自动验收:

- 对所有 BookLite bundled lang string leaf:
  1. 用旧 `CubexText.color(oldTemplateWithSamplePlaceholders)` 得到 legacy section string。
  2. 用新 MiniMessage + TagResolver 得到 Component。
  3. 用 `LegacyComponentSerializer.legacySection().serialize(component)` 得到 legacy section string。
  4. 比较两者颜色码和纯文本。
- 对命令 usage 里的 `\\<id\\>` / `\\<days\\>` 加专门测试, 确认显示为 `<id>` / `<days>` 而不是 placeholder。
- 对 placeholder 使用 sample:
  - `<input>`, `<title>`, `<short_id>`, `<actual>`, `<max>`, `<total>`, `<cache>`, `<last_accessed>`, `<uninstall>`, `<count>`, `<days>`, `<page>`, `<author>`, `<pages>`, `<hash>`, `<created>`, `<updated>`

手工验收:

- runServer 启动, 执行 `/booklite`, `/booklite status`, `/booklite reload`。
- 观察 chat 中 `<prefix>`、颜色、命令帮助 `<id>` 字面显示。
- 修改一份旧格式 lang 文件后重启, 确认生成 backup、文件被迁移、视觉不变。

## 5. 风险与取舍

### 5.1 已自定义 lang 文件

风险:

- 管理员可能写了不符合旧规则的 `%...%` 或 `<...>`。
- 旧文本里可能已经人工写了 MiniMessage tag; 自动迁移默认会 escape, 视觉可能不同。

处理:

- 迁移前必须备份。
- 迁移 report 列出所有可疑 leaf path。
- 对 BookLite 试点, 遇到可疑无法判断内容默认 abort, 不做半迁移。
- 后续可提供 `migration.lenient=true` 配置, 但不是 BookLite 首次试点默认。

### 5.2 不可逆性

风险:

- `&`/hex 到 MiniMessage 基本可逆, 但用户手动编辑后的 MiniMessage 不能自动还原到 legacy。
- YAML save 会丢注释/格式。

处理:

- 备份是唯一可靠回退。
- 版本 key 一旦到 2, 不自动降级。
- release notes 必须写明"阶段 B 是内容迁移, 回退需恢复 backup"。

### 5.3 MiniMessage 与 legacy 语义差异

- `&k` -> `<obfuscated>` 视觉接近, 但不同服务端/客户端显示细节可能略有差异。
- `&r` -> `<reset>` 会重置所有样式; 旧 legacy 的状态机与 MiniMessage 嵌套 tag 在复杂嵌套时可能不同。迁移器用非闭合连续 style tag 降低差异。
- `&#RRGGBB` -> `<#RRGGBB>` 等价性较高, 但旧服务器/客户端对 RGB 支持仍取决于 Minecraft 版本。
- MiniMessage 支持 gradient/rainbow/click/hover, 但 BookLite 首次试点不引入这些新能力, 避免视觉变化。

### 5.4 发送路径

- 玩家 chat: Component 发送优先。
- 控制台: Component 优先, legacy fallback。
- Bukkit 旧 API surface(item meta、book meta、scoreboard、title/actionbar 旧方法): 使用 legacy serializer。
- Folia: i18n 本身不调度; 若异步调用 send, 必须由调用插件保证线程/region 语义, 或通过 cubex-scheduler 派发。

### 5.5 依赖与维护风险

- `adventure-platform-bukkit` 覆盖目标版本范围, 但官方标注不再维护。BookLite 试点必须保留 legacy fallback send, 并在 Spigot/Paper 1.16、1.20.1、1.21.x 至少各跑一次 smoke。
- 每插件 relocate Adventure 会增加 jar 体积; 但避免跨插件 classpath 冲突, 对公开插件更稳。
- 已经有 Adventure 的 RuleGems/Metro/Railway 现代化时要统一版本和 relocate, 不可复制 BookLite 的配置后直接叠加。

## 6. BookLite 阶段 B 实施顺序建议

1. 在 `cubex-i18n` 实现 MiniMessage renderer、Component/legacy 双出口、BukkitAudiences 生命周期。
2. 在 `cubex-config` 实现 `MigrationRunner` 与 YAML backup/atomic save。
3. 给 legacy-to-MiniMessage converter 写纯单元测试, 覆盖 `&c`, `&l`, `&r`, `&#RRGGBB`, `%name%`, `{prefix}`, 字面 `<id>`。
4. 重写 BookLite bundled lang 为 `lang-version: 2`。
5. 给 BookLite 旧 lang 文件迁移加 fixture 测试, 验证 backup 和视觉等价。
6. BookLite runServer: 旧文件首次启动迁移、重启不重复迁移、`/booklite reload` 不重复迁移。
7. Review 通过后再考虑 MountLicense, 不批量推广。

## 7. Review 结论与已定方向(以此为准)

**设计稿通过。** 它正确处理了 placeholder 注入安全(`<name>`+`Placeholder.unparsed`)、版本化迁移(备份/原子保存/abort/tokenization/字面 `<id>` 转义)、视觉等价验收。以下为已定收紧项:

- **发送方案(用户已定):不引入 `adventure-platform-bukkit` / `BukkitAudiences`**。BookLite 首次现代化用 **MiniMessage parse → `LegacyComponentSerializer.legacySection()` 序列化成 §-string → 普通 Bukkit `sendMessage(String)`**。
  - `cubex-i18n` 依赖仅:`adventure-api` + `adventure-text-minimessage` + `adventure-text-serializer-legacy`(经 BOM 4.25.0 对齐);**不含 platform-bukkit**。`cubex-core` 仍零第三方依赖。
  - `ColorMode.MINIMESSAGE`:`component(...)` 内部可建 Component;但 `message(...)`/`send(...)` 一律序列化成 §-string 发送。**Component/Audience 发送推迟到将来某插件真要 hover/click 时再引入**(YAGNI)。
  - 全平台一致(只发 §-string)、零视觉变化、避开不维护依赖与版本对齐风险、jar 更小。§1.1/§1.3 的 platform-bukkit/BukkitAudiences 内容据此作废。
- **保留**:统一版本化迁移框架(本轮首次落地)、`<name>`/unparsed、tokenized 转换器、严格 abort+备份、版本键幂等、视觉等价测试(新旧都序列化成 §-string 比对)。
- **adventure relocate**:BookLite 现在打包 adventure,`shadowJar` 把 `net.kyori` relocate 到 `org.cubexmc.booklite.libs.kyori`;不 minimize;不 relocate `org.cubexmc.*`。
- **小提醒(实现+测试)**:用视觉等价测试确认 `<reset>` 与 `&r` 一致、非闭合连续 style tag 的复杂嵌套与 legacy 一致。
- **验收标准转变**:这是首个**内容变更**轮 —— BookLite 的 lang 文件会从 legacy 变成 MiniMessage v2,**不再 byte-equal**;验收以**视觉等价**(渲染输出相同)+ **迁移正确性**(旧文件→备份+迁移+视觉不变、重启/reload 不重复迁移)为准。
