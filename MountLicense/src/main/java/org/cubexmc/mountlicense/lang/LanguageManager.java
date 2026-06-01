package org.cubexmc.mountlicense.lang;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.cubexmc.mountlicense.MountLicensePlugin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

public class LanguageManager {

    private final MountLicensePlugin plugin;
    private String locale;
    private FileConfiguration messages;
    private String prefix = "";

    public LanguageManager(MountLicensePlugin plugin, String locale) {
        this.plugin = plugin;
        this.locale = locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public void load() {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + locale + ".yml");
        if (!langFile.exists()) {
            plugin.getLogger().warning("Language file " + locale + ".yml missing, falling back to zh_CN.");
            langFile = new File(plugin.getDataFolder(), "lang" + File.separator + "zh_CN.yml");
        }
        if (langFile.exists()) {
            messages = YamlConfiguration.loadConfiguration(langFile);
        } else {
            messages = new YamlConfiguration();
        }
        prefix = colorize(messages.getString("prefix", ""));
    }

    public String raw(String key) {
        String value = messages != null ? messages.getString(key) : null;
        return value == null ? key : value;
    }

    public List<String> rawList(String key) {
        if (messages == null) return new ArrayList<>();
        List<String> list = messages.getStringList(key);
        return list == null ? new ArrayList<>() : list;
    }

    public String msg(String key, Map<String, String> placeholders) {
        return format(raw(key), placeholders);
    }

    public String msg(String key) {
        return format(raw(key), null);
    }

    public List<String> msgList(String key, Map<String, String> placeholders) {
        List<String> out = new ArrayList<>();
        for (String line : rawList(key)) {
            out.add(format(line, placeholders));
        }
        return out;
    }

    public void send(CommandSender to, String key) {
        send(to, key, null);
    }

    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        if (to == null) return;
        to.sendMessage(msg(key, placeholders));
    }

    public void sendActionBar(Player to, String key) {
        sendActionBar(to, key, null);
    }

    public void sendActionBar(Player to, String key, Map<String, String> placeholders) {
        if (to == null) return;
        to.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(msg(key, placeholders)));
    }

    private String format(String raw, Map<String, String> placeholders) {
        if (raw == null) return "";
        String out = raw.replace("{prefix}", prefix);
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                out = out.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
            }
        }
        return colorize(out);
    }

    private static String colorize(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public String prefix() { return prefix; }
}
