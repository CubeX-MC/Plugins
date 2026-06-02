package org.cubexmc.mountlicense.lang;

import java.util.List;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nService;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;
import org.cubexmc.mountlicense.MountLicensePlugin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

public class LanguageManager {

    private final MountLicensePlugin plugin;
    private final I18nService i18n;
    private String locale;

    public LanguageManager(MountLicensePlugin plugin, String locale) {
        this.plugin = plugin;
        this.locale = locale;
        this.i18n = I18nServices.create(plugin, I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale(locale)
                .defaultLocale("zh_CN")
                .fallbackLocales(List.of("zh_CN"))
                .bundledLocales(List.of("zh_CN", "en_US"))
                .prefixKey("prefix")
                .prefixToken("<prefix>")
                .missingKeyMode(MissingKeyMode.RETURN_KEY)
                .placeholderStyles(List.of(PlaceholderStyle.MINIMESSAGE_TAG))
                .colorMode(ColorMode.MINIMESSAGE));
    }

    public void setLocale(String locale) {
        this.locale = locale;
        i18n.setCurrentLocale(locale);
    }

    public void load() {
        java.io.File langFile = new java.io.File(plugin.getDataFolder(),
                "lang" + java.io.File.separator + locale + ".yml");
        if (!langFile.exists() && !"zh_CN".equals(locale)) {
            plugin.getLogger().warning("Language file " + locale + ".yml missing, falling back to zh_CN.");
        }
        i18n.reload();
    }

    public String raw(String key) {
        return i18n.raw(key);
    }

    public List<String> rawList(String key) {
        return i18n.rawList(key);
    }

    public String msg(String key, Map<String, String> placeholders) {
        return i18n.message(key, placeholders);
    }

    public String msg(String key) {
        return i18n.message(key);
    }

    public List<String> msgList(String key, Map<String, String> placeholders) {
        return i18n.messageList(key, placeholders);
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

    public String prefix() { return msg("prefix"); }
}
