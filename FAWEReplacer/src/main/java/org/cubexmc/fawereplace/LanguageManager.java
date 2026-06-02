package org.cubexmc.fawereplace;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nService;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;

/**
 * 多语言管理器
 * 负责加载和管理插件的多语言文本
 */
public class LanguageManager {

    private final CubexPlugin plugin;
    private final Logger logger;
    private final I18nService i18n;
    private String currentLanguage;

    /**
     * 构造函数
     * 
     * @param plugin   插件实例
     * @param language 语言代码 (如 zh_CN, en_US)
     */
    public LanguageManager(CubexPlugin plugin, String language) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.currentLanguage = language;
        this.i18n = I18nServices.create(plugin, I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale(language)
                .defaultLocale("zh_CN")
                .fallbackLocales(List.of("zh_CN"))
                .bundledLocales(List.of("zh_CN", "en_US"))
                .prefixKey("prefix")
                .prefixToken("<prefix>")
                .missingKeyMode(MissingKeyMode.RETURN_KEY)
                .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE));
        loadLanguage(language);
    }

    /**
     * 加载指定语言文件
     * 
     * @param language 语言代码
     */
    public void loadLanguage(String language) {
        this.currentLanguage = language;
        i18n.setCurrentLocale(language);

        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");

        // 加载语言文件
        try {
            if (!langFile.exists() && !"zh_CN".equals(language)) {
                logger.warning(getMessage("log.language_fallback", "file", language + ".yml"));
            }
            i18n.reload();
            int count = countMessages(language);
            logger.info(
                    getMessage("log.language_loaded", "language", language, "count", String.valueOf(count)));
        } catch (Exception e) {
            logger.severe("Failed to load language file: " + language + ".yml");
            e.printStackTrace();
        }
    }

    /**
     * 获取翻译文本
     * 
     * @param key 消息键
     * @return 翻译后的文本，如果键不存在则返回键本身
     */
    public String getMessage(String key) {
        return i18n.message(key);
    }

    /**
     * 获取翻译文本并替换占位符
     * 
     * @param key          消息键
     * @param replacements 替换内容，格式为 {placeholder1, value1, placeholder2, value2,
     *                     ...}
     * @return 翻译并替换后的文本
     */
    public String getMessage(String key, Object... replacements) {
        if (replacements.length % 2 != 0) {
            logger.warning(getMessage("log.invalid_replacements", "key", key));
            return getMessage(key);
        }
        java.util.Map<String, Object> placeholders = new java.util.HashMap<>();
        for (int i = 0; i < replacements.length; i += 2) {
            String placeholder = String.valueOf(replacements[i]);
            placeholders.put(placeholder, replacements[i + 1]);
        }
        return i18n.message(key, placeholders);
    }

    /**
     * 重新加载当前语言
     */
    public void reload() {
        loadLanguage(currentLanguage);
    }

    /**
     * 重新加载并切换到新语言
     * 
     * @param language 新的语言代码
     */
    public void reload(String language) {
        loadLanguage(language);
    }

    /**
     * 获取当前语言代码
     * 
     * @return 当前语言代码
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    private int countMessages(String language) {
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists() && !"zh_CN".equals(language)) {
            langFile = new File(plugin.getDataFolder(), "lang/zh_CN.yml");
        }
        if (!langFile.exists()) {
            return 0;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(langFile);
        int count = 0;
        for (String key : config.getKeys(true)) {
            if (config.isString(key)) {
                count++;
            }
        }
        return count;
    }
}
