package org.cubexmc.i18n;

import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;

public class BookLiteLanguageAdapter {

    private final I18nService i18n;

    public BookLiteLanguageAdapter(I18nService i18n) {
        this.i18n = i18n;
    }

    public void setLocale(String locale) {
        i18n.setCurrentLocale(locale);
    }

    public void load() {
        i18n.reload();
    }

    public String raw(String key) {
        return i18n.raw(key);
    }

    public List<String> rawList(String key) {
        return i18n.rawList(key);
    }

    public String msg(String key) {
        return i18n.message(key);
    }

    public String msg(String key, Map<String, String> placeholders) {
        return i18n.message(key, placeholders);
    }

    public List<String> msgList(String key, Map<String, String> placeholders) {
        return i18n.messageList(key, placeholders);
    }

    public void send(CommandSender to, String key) {
        send(to, key, null);
    }

    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        if (to != null) {
            to.sendMessage(msg(key, placeholders));
        }
    }
}
