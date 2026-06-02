package org.cubexmc.i18n;

import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.cubexmc.core.Reloadable;

public interface I18nService extends Reloadable {
    String currentLocale();

    void setCurrentLocale(String locale);

    @Override
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
