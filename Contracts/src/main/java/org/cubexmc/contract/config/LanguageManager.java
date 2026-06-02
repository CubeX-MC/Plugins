package org.cubexmc.contract.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.model.PayoutCondition;
import org.cubexmc.contract.util.Text;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nService;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LanguageManager {
    private final ContractPlugin plugin;
    private final I18nService i18n;
    private FileConfiguration language;

    public LanguageManager(ContractPlugin plugin) {
        this.plugin = plugin;
        this.i18n = I18nServices.create(plugin, I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale(() -> sanitizeLanguageName(plugin.getConfig().getString("language", "zh_CN")))
                .defaultLocale("zh_CN")
                .fallbackLocales(List.of("zh_CN"))
                .bundledLocales(List.of("zh_CN", "en_US"))
                .prefixKey("prefix")
                .prefixToken("<prefix>")
                .keyPrefix("messages.")
                .missingKeyMode(MissingKeyMode.RETURN_KEY)
                .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE));
    }

    public void load() {
        String configured = plugin.getConfig().getString("language", "zh_CN");
        String languageName = sanitizeLanguageName(configured);
        File file = new File(plugin.getDataFolder(), "lang/" + languageName + ".yml");
        if (!file.exists()) {
            file = new File(plugin.getDataFolder(), "lang/zh_CN.yml");
        }
        language = YamlConfiguration.loadConfiguration(file);
        i18n.reload();
    }

    public String message(String path, Map<String, String> placeholders) {
        return i18n.message(path, placeholders == null ? Map.of() : placeholders);
    }

    public String message(String path) {
        return message(path, Map.of());
    }

    public String status(ContractStatus status) {
        String key = "status." + status.name().toLowerCase(Locale.ROOT);
        String raw = language.getString(key, status.name());
        return Text.color(raw);
    }

    public String type(ContractType type) {
        String key = "types." + type.name().toLowerCase(Locale.ROOT);
        String raw = language.getString(key, type.name());
        return Text.color(raw);
    }

    public String role(ParticipantRole role) {
        String key = "roles." + role.name().toLowerCase(Locale.ROOT);
        String raw = language.getString(key, role.name());
        return Text.color(raw);
    }

    public String condition(PayoutCondition condition) {
        String key = "conditions." + condition.name().toLowerCase(Locale.ROOT);
        String raw = language.getString(key, condition.name());
        return Text.color(raw);
    }

    public String term(String key, String fallback) {
        String raw = language.getString("terms." + key, fallback);
        return Text.color(raw);
    }

    private String sanitizeLanguageName(String configured) {
        if (configured == null || configured.isBlank()) {
            return "zh_CN";
        }
        String sanitized = configured.replaceAll("[^A-Za-z0-9_-]", "");
        return sanitized.isBlank() ? "zh_CN" : sanitized;
    }
}
