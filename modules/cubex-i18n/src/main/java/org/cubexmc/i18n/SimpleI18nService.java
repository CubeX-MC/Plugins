package org.cubexmc.i18n;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;
import org.cubexmc.core.CubexText;

final class SimpleI18nService implements I18nService {

    private static final Pattern MINIMESSAGE_PLACEHOLDER_NAME = Pattern.compile("[a-z0-9_:-]+");

    private final CubexPlugin plugin;
    private final I18nOptions options;
    private final CubexText text = new CubexText();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private final Map<String, YamlConfiguration> languages = new HashMap<>();
    private String currentLocale;
    private String activeLocale;
    private String prefix = "";
    private String prefixTemplate = "";

    SimpleI18nService(CubexPlugin plugin, I18nOptions options) {
        this.plugin = plugin;
        this.options = options;
        this.currentLocale = options.currentLocale();
    }

    @Override
    public String currentLocale() {
        return currentLocale;
    }

    @Override
    public void setCurrentLocale(String locale) {
        this.currentLocale = locale;
    }

    @Override
    public void reload() {
        languages.clear();
        if (options.hasCurrentLocaleSupplier() || currentLocale == null || currentLocale.isBlank()) {
            currentLocale = options.currentLocale();
        }
        activeLocale = firstAvailableLocale(currentLocale);
        YamlConfiguration active = configuration(activeLocale);
        prefixTemplate = active.getString(options.prefixKey(), "");
        prefix = color(prefixTemplate);
    }

    @Override
    public String raw(String key) {
        return raw(key, currentLocale);
    }

    @Override
    public String raw(String key, String locale) {
        String resolved = lookup(locale, key);
        if (resolved != null) {
            return resolved;
        }
        return missing(key);
    }

    @Override
    public List<String> rawList(String key) {
        return rawList(key, currentLocale);
    }

    @Override
    public List<String> rawList(String key, String locale) {
        String resolvedKey = resolveKey(key);
        for (String candidate : localeChain(locale)) {
            YamlConfiguration configuration = configuration(candidate);
            if (configuration.isList(resolvedKey)) {
                return configuration.getStringList(resolvedKey);
            }
        }
        return new ArrayList<>();
    }

    @Override
    public String message(String key) {
        return message(key, Map.of());
    }

    @Override
    public String message(String key, Map<String, ?> placeholders) {
        return message(key, currentLocale, placeholders);
    }

    @Override
    public String message(String key, String locale, Map<String, ?> placeholders) {
        return format(raw(key, locale), placeholders);
    }

    @Override
    public String message(String key, Object... positionalArgs) {
        String message = raw(key);
        if (positionalArgs != null && options.placeholderStyles().contains(PlaceholderStyle.POSITIONAL_PERCENT_INDEX)) {
            for (int i = 0; i < positionalArgs.length; i++) {
                message = message.replace("%" + (i + 1), value(positionalArgs[i]));
            }
        }
        return format(message, Map.of());
    }

    @Override
    public List<String> messageList(String key, Map<String, ?> placeholders) {
        List<String> formatted = new ArrayList<>();
        for (String line : rawList(key)) {
            formatted.add(format(line, placeholders));
        }
        return formatted;
    }

    @Override
    public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        if (sender != null) {
            sender.sendMessage(message(key, placeholders));
        }
    }

    private String lookup(String locale, String key) {
        String resolvedKey = resolveKey(key);
        for (String candidate : localeChain(locale)) {
            YamlConfiguration configuration = configuration(candidate);
            String message = configuration.getString(resolvedKey);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private String firstAvailableLocale(String preferred) {
        for (String candidate : localeChain(preferred)) {
            File file = languageFile(candidate);
            if (file.exists() || plugin.getResource(resourcePath(candidate)) != null) {
                return candidate;
            }
        }
        return preferred;
    }

    private List<String> localeChain(String preferred) {
        Set<String> chain = new LinkedHashSet<>();
        addLocale(chain, preferred);
        addLocale(chain, options.defaultLocale());
        for (String fallback : options.fallbackLocales()) {
            addLocale(chain, fallback);
        }
        for (String bundled : options.bundledLocales()) {
            addLocale(chain, bundled);
        }
        return new ArrayList<>(chain);
    }

    private void addLocale(Set<String> locales, String locale) {
        if (locale != null && !locale.isBlank()) {
            locales.add(locale);
        }
    }

    private YamlConfiguration configuration(String locale) {
        if (locale == null) {
            return new YamlConfiguration();
        }
        return languages.computeIfAbsent(locale, this::loadConfiguration);
    }

    private YamlConfiguration loadConfiguration(String locale) {
        File file = languageFile(locale);
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        return new YamlConfiguration();
    }

    private File languageFile(String locale) {
        return new File(plugin.getDataFolder(), resourcePath(locale));
    }

    private String resourcePath(String locale) {
        return options.languageDirectory() + File.separator + locale + ".yml";
    }

    private String resolveKey(String key) {
        if (key == null) {
            return "";
        }
        String prefix = options.keyPrefix();
        if (prefix.isEmpty() || key.startsWith(prefix)) {
            return key;
        }
        return prefix + key;
    }

    private String missing(String key) {
        if (options.warnOnMissingKey()) {
            plugin.getLogger().warning("Missing message: " + key);
        }
        if (options.missingKeyMode() == MissingKeyMode.RETURN_EMPTY) {
            return "";
        }
        if (options.missingKeyMode() == MissingKeyMode.RETURN_MISSING_MESSAGE_PREFIX) {
            return "Missing message: " + key;
        }
        return key == null ? "" : key;
    }

    private String format(String raw, Map<String, ?> placeholders) {
        if (raw == null) {
            return "";
        }
        if (options.colorMode() == ColorMode.MINIMESSAGE) {
            return renderMiniMessage(raw, placeholders);
        }
        String formatted = raw;
        if (!options.prefixToken().isEmpty()) {
            formatted = formatted.replace(options.prefixToken(), prefix == null ? "" : prefix);
        }
        if (placeholders != null) {
            for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
                String key = entry.getKey();
                String value = value(entry.getValue());
                if (options.placeholderStyles().contains(PlaceholderStyle.PERCENT_NAME)) {
                    formatted = formatted.replace("%" + key + "%", value);
                }
                if (options.placeholderStyles().contains(PlaceholderStyle.BRACE_NAME)) {
                    formatted = formatted.replace("{" + key + "}", value);
                }
            }
        }
        return color(formatted);
    }

    private String color(String input) {
        ColorMode colorMode = options.colorMode();
        if (colorMode == null) {
            return input == null ? "" : input;
        }
        if (colorMode == ColorMode.MINIMESSAGE) {
            return renderMiniMessage(input, Map.of());
        }
        return text.color(input);
    }

    private String renderMiniMessage(String template, Map<String, ?> placeholders) {
        String resolvedTemplate = template == null ? "" : template;
        if (!options.prefixToken().isEmpty()) {
            resolvedTemplate = resolvedTemplate.replace(options.prefixToken(), prefixTemplate == null ? "" : prefixTemplate);
        }
        TagResolver resolver = buildResolver(placeholders);
        var component = miniMessage.deserialize(resolvedTemplate, resolver);
        return legacySerializer.serialize(component);
    }

    private TagResolver buildResolver(Map<String, ?> placeholders) {
        TagResolver.Builder builder = TagResolver.builder();
        if (placeholders != null && options.placeholderStyles().contains(PlaceholderStyle.MINIMESSAGE_TAG)) {
            for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
                String name = entry.getKey();
                if (name != null && MINIMESSAGE_PLACEHOLDER_NAME.matcher(name).matches()) {
                    builder.resolver(Placeholder.unparsed(name, value(entry.getValue())));
                }
            }
        }
        return builder.build();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
