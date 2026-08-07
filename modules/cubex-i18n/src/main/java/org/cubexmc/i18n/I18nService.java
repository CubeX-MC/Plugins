package org.cubexmc.i18n;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
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

    /**
     * {@link #message(String)} as an Adventure {@link Component}.
     *
     * <p>Under {@link ColorMode#MINIMESSAGE} this is the lossless form: the service already builds a
     * component before flattening it to a legacy section string, so callers on a Paper server can
     * take the component and skip a round trip that silently drops anything legacy cannot encode
     * (hover, click, fonts, translatable children).
     */
    Component component(String key);

    /** Placeholder-aware form of {@link #component(String)}. */
    Component component(String key, Map<String, ?> placeholders);

    /** {@link #messageList(String, Map)} as components, for item lore and multi-line output. */
    List<Component> componentList(String key, Map<String, ?> placeholders);

    /**
     * Parses a string this service already rendered — the output of {@link #message(String, Map)},
     * possibly concatenated with more of the same — back into a {@link Component}.
     *
     * <p>Exists so callers never have to guess which legacy serializer the service used. A GUI that
     * assembles a label out of several translated fragments cannot use {@link #component(String)},
     * but it still needs a component at the display boundary, and picking its own
     * {@code LegacyComponentSerializer} is how a plugin ends up with hex colours that render as
     * literal {@code §x} garbage.
     */
    Component componentOf(String renderedMessage);

    void send(CommandSender sender, String key, Map<String, ?> placeholders);
}
