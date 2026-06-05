package org.cubexmc.metro.manager;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nService;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.update.LanguageUpdater;
import org.cubexmc.metro.update.MetroMigrations;
import org.cubexmc.metro.util.MetroTextRenderer;

/**
 * 管理多语言消息的类
 */
public class LanguageManager {

    private final Metro plugin;
    private final I18nService i18n;
    private final Map<String, YamlConfiguration> languageFiles = new HashMap<>();
    private String defaultLanguage = "zh_CN";
    private String currentLanguage = "zh_CN";

    /**
     * 构造语言管理器
     *
     * @param plugin 插件实例
     */
    public LanguageManager(Metro plugin) {
        this.plugin = plugin;
        this.i18n = I18nServices.create(plugin, I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale(() -> currentLanguage)
                .defaultLocale(defaultLanguage)
                .fallbackLocales(List.of("en_US", "zh_CN"))
                .bundledLocales(MetroMigrations.BUNDLED_LANGUAGES)
                .prefixToken("<prefix>")
                .missingKeyMode(MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX)
                .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG, PlaceholderStyle.POSITIONAL_PERCENT_INDEX))
                .colorMode(ColorMode.MINIMESSAGE));
        loadLanguages();
    }

    /**
     * 加载所有语言文件
     */
    public void loadLanguages() {
        // 清空已加载的语言
        languageFiles.clear();

        // 获取配置中的默认语言
        defaultLanguage = plugin.getConfig().getString("settings.default_language", "zh_CN");
        currentLanguage = defaultLanguage;

        // 确保语言目录存在
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        // 保存内置语言文件（首次运行时会复制到插件数据目录，之后会自动合并新增键）
        String[] bundledLanguages = new String[] { "zh_CN", "zh_TW", "en_US", "de_DE", "es_ES", "nl_NL", "tr_TR" };
        for (String lang : bundledLanguages) {
            saveDefaultLanguageFile(lang);
        }

        // 加载语言目录下的所有yml文件
        File[] langFiles = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (langFiles != null) {
            for (File file : langFiles) {
                String langCode = file.getName().replace(".yml", "");
                try {
                    YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(file);
                    languageFiles.put(langCode, langConfig);
                    plugin.getLogger().info("已加载语言文件: " + langCode);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "加载语言文件失败: " + file.getName(), e);
                }
            }
        }

        // 如果没有找到默认语言，则尝试加载内置语言
        if (!languageFiles.containsKey(defaultLanguage)) {
            try {
                InputStream inputStream = plugin.getResource("lang/" + defaultLanguage + ".yml");
                if (inputStream != null) {
                    YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    languageFiles.put(defaultLanguage, defaultConfig);
                    plugin.getLogger().info("Loaded default language: " + defaultLanguage);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load default language: " + defaultLanguage, e);
            }
        }
        i18n.setCurrentLocale(currentLanguage);
        i18n.reload();
    }

    /**
     * 保存默认语言文件并自动合并新的语言键
     *
     * @param langCode 语言代码
     */
    private void saveDefaultLanguageFile(String langCode) {
        File langFile = new File(plugin.getDataFolder(), "lang/" + langCode + ".yml");
        String resourcePath = "lang/" + langCode + ".yml";

        if (!langFile.exists()) {
            // 首次运行，复制默认文件
            plugin.saveResource(resourcePath, false);
        } else {
            // 文件已存在，迁移到当前语言版本并合并缺失键（不覆盖用户修改）
            LanguageUpdater.merge(plugin, langFile, resourcePath);
        }
    }

    /**
     * 获取语言消息
     *
     * @param key 消息键
     * @return 格式化后的消息
     */
    public String getMessage(String key) {
        return getMessage(key, currentLanguage);
    }

    /**
     * 获取语言消息
     *
     * @param key 消息键
     * @param langCode 语言代码
     * @return 格式化后的消息
     */
    public String getMessage(String key, String langCode) {
        return MetroTextRenderer.renderPreservingPlaceholders(rawMessage(key, langCode));
    }

    /**
     * 使用参数获取格式化的语言消息（数字占位符）
     *
     * @param key 消息键
     * @param args 替换参数，格式为 %1, %2 等
     * @return 格式化后的消息
     */
    public String getMessage(String key, Object... args) {
        String message = rawMessage(key, currentLanguage);
        Map<String, Object> positional = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            positional.put("arg" + (i + 1), args[i]);
        }
        return MetroTextRenderer.render(message, positional);
    }

    /**
     * 使用命名参数获取格式化的语言消息
     *
     * @param key 消息键
     * @param namedArgs 命名参数映射，格式为 {key} = value
     * @return 格式化后的消息
     */
    public String getMessage(String key, Map<String, Object> namedArgs) {
        return MetroTextRenderer.render(rawMessage(key, currentLanguage), namedArgs);
    }

    /**
     * 创建命名参数映射的便捷方法
     *
     * @return 新的参数映射
     */
    public static Map<String, Object> args() {
        return new HashMap<>();
    }

    /**
     * 向参数映射添加参数的便捷方法
     *
     * @param args 参数映射
     * @param key 参数名
     * @param value 参数值
     * @return 参数映射（链式调用）
     */
    public static Map<String, Object> put(Map<String, Object> args, String key, Object value) {
        args.put(key, value);
        return args;
    }

    private String rawMessage(String key, String langCode) {
        YamlConfiguration langConfig = languageFiles.get(langCode);
        if (langConfig == null || !langConfig.contains(key)) {
            langConfig = languageFiles.get(defaultLanguage);
        }
        if (langConfig != null && langConfig.contains(key)) {
            return langConfig.getString(key, "");
        }
        return "Missing message: " + key;
    }
}
