package org.cubexmc.fawereplace;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 多语言管理器
 * 负责加载和管理插件的多语言文本
 */
public class LanguageManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, String> messages = new HashMap<>();
    private String currentLanguage;

    /**
     * 构造函数
     * 
     * @param plugin   插件实例
     * @param language 语言代码 (如 zh_CN, en_US)
     */
    public LanguageManager(JavaPlugin plugin, String language) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.currentLanguage = language;
        loadLanguage(language);
    }

    /**
     * 加载指定语言文件
     * 
     * @param language 语言代码
     */
    public void loadLanguage(String language) {
        messages.clear();
        this.currentLanguage = language;

        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");

        // 如果外部文件不存在，从资源中复制
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + language + ".yml", false);
        }

        // 加载语言文件
        try {
            YamlConfiguration config;
            if (langFile.exists()) {
                config = YamlConfiguration.loadConfiguration(langFile);
            } else {
                // 如果外部文件和资源文件都不存在，尝试从内部资源加载
                InputStream stream = plugin.getResource("lang/" + language + ".yml");
                if (stream != null) {
                    config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                } else {
                    logger.warning("Language file not found: " + language + ".yml, falling back to zh_CN");
                    // 回退到中文
                    if (!language.equals("zh_CN")) {
                        loadLanguage("zh_CN");
                        return;
                    }
                    return;
                }
            }

            // 加载所有消息
            for (String key : config.getKeys(true)) {
                if (config.isString(key)) {
                    messages.put(key, config.getString(key));
                }
            }

            logger.info(
                    getMessage("log.language_loaded", "language", language, "count", String.valueOf(messages.size())));
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
        return messages.getOrDefault(key, key);
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
        String message = getMessage(key);

        if (replacements.length % 2 != 0) {
            logger.warning(getMessage("log.invalid_replacements", "key", key));
            return message;
        }

        for (int i = 0; i < replacements.length; i += 2) {
            String placeholder = String.valueOf(replacements[i]);
            String value = String.valueOf(replacements[i + 1]);
            message = message.replace("{" + placeholder + "}", value);
        }

        return message;
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
}
