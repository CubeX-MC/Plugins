package org.cubexmc.contract.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.model.PayoutCondition;
import org.cubexmc.contract.util.Text;

import java.io.File;
import java.util.Locale;
import java.util.Map;

public final class LanguageManager {
    private final ContractPlugin plugin;
    private FileConfiguration language;

    public LanguageManager(ContractPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        String configured = plugin.getConfig().getString("language", "zh_CN");
        String languageName = sanitizeLanguageName(configured);
        File file = new File(plugin.getDataFolder(), "lang/" + languageName + ".yml");
        if (!file.exists()) {
            file = new File(plugin.getDataFolder(), "lang/zh_CN.yml");
        }
        language = YamlConfiguration.loadConfiguration(file);
    }

    public String message(String path, Map<String, String> placeholders) {
        String raw = language.getString("messages." + path, path);
        String prefix = language.getString("prefix", "");
        raw = raw.replace("%prefix%", prefix);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return Text.color(raw);
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
