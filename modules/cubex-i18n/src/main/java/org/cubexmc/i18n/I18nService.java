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

    /**
     * The raw template for {@code key} from the first locale in the fallback chain that defines it,
     * or {@code null} when none does.
     *
     * <p>{@link #raw(String)} cannot express "absent": it returns the key itself (or an empty
     * string, or a marker) depending on {@link MissingKeyMode}, so a caller that wants to supply its
     * own default has no way to tell a real translation from a miss.
     */
    String rawOrNull(String key);

    /** Locale-scoped form of {@link #rawOrNull(String)}. */
    String rawOrNull(String key, String locale);

    List<String> rawList(String key);

    List<String> rawList(String key, String locale);

    String message(String key);

    String message(String key, Map<String, ?> placeholders);

    String message(String key, String locale, Map<String, ?> placeholders);

    String message(String key, Object... positionalArgs);

    List<String> messageList(String key, Map<String, ?> placeholders);

    void send(CommandSender sender, String key, Map<String, ?> placeholders);
}
