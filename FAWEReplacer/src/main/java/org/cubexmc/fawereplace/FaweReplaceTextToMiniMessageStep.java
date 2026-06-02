package org.cubexmc.fawereplace;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.cubexmc.config.MigrationContext;
import org.cubexmc.config.MigrationStep;

public final class FaweReplaceTextToMiniMessageStep implements MigrationStep {

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[a-zA-Z0-9_:-]+");
    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"),
            Map.entry('1', "dark_blue"),
            Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"),
            Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"),
            Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"),
            Map.entry('a', "green"),
            Map.entry('b', "aqua"),
            Map.entry('c', "red"),
            Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"),
            Map.entry('f', "white"),
            Map.entry('k', "obfuscated"),
            Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"),
            Map.entry('n', "underlined"),
            Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private final int fromVersion;
    private final int toVersion;

    public FaweReplaceTextToMiniMessageStep(int fromVersion, int toVersion) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    @Override
    public int fromVersion() {
        return fromVersion;
    }

    @Override
    public int toVersion() {
        return toVersion;
    }

    @Override
    public String description() {
        return "Convert FAWEReplace legacy section colors and brace placeholders to MiniMessage.";
    }

    @Override
    public void migrate(MigrationContext context) {
        convertSection(context.yaml(), "");
    }

    public String convert(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        StringBuilder output = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '&' || current == '§') {
                int consumed = appendLegacyTag(input, i, output);
                if (consumed > i) {
                    i = consumed;
                    continue;
                }
            }
            if (current == '{') {
                int end = input.indexOf('}', i + 1);
                if (end > i) {
                    String name = input.substring(i + 1, end);
                    if (PLACEHOLDER_NAME.matcher(name).matches()) {
                        output.append('<').append(name.toLowerCase(Locale.ROOT)).append('>');
                        i = end;
                        continue;
                    }
                }
            }
            appendLiteral(output, current);
        }
        return output.toString();
    }

    private void convertSection(ConfigurationSection section, String basePath) {
        for (String key : section.getKeys(false)) {
            String path = basePath.isEmpty() ? key : basePath + "." + key;
            if (section.isConfigurationSection(key)) {
                convertSection(section.getConfigurationSection(key), path);
            } else if (section.isString(key)) {
                section.set(key, convert(section.getString(key, "")));
            } else if (section.isList(key)) {
                List<?> values = section.getList(key);
                if (values != null && values.stream().allMatch(value -> value instanceof String)) {
                    section.set(key, values.stream()
                            .map(value -> convert((String) value))
                            .toList());
                }
            }
        }
    }

    private int appendLegacyTag(String input, int markerIndex, StringBuilder output) {
        if (markerIndex + 1 >= input.length()) {
            return markerIndex;
        }
        if (input.charAt(markerIndex + 1) == '#'
                && markerIndex + 7 < input.length()
                && isHex(input, markerIndex + 2, markerIndex + 8)) {
            output.append("<reset><#").append(input, markerIndex + 2, markerIndex + 8).append('>');
            return markerIndex + 7;
        }
        char code = Character.toLowerCase(input.charAt(markerIndex + 1));
        String tag = LEGACY_TAGS.get(code);
        if (tag == null) {
            return markerIndex;
        }
        if (isLegacyColor(code) || code == 'r') {
            output.append("<reset>");
            if (code != 'r') {
                output.append('<').append(tag).append('>');
            }
        } else {
            output.append('<').append(tag).append('>');
        }
        return markerIndex + 1;
    }

    private boolean isLegacyColor(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    private boolean isHex(String input, int startInclusive, int endExclusive) {
        for (int index = startInclusive; index < endExclusive; index++) {
            char c = input.charAt(index);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private void appendLiteral(StringBuilder output, char current) {
        if (current == '<') {
            output.append('\\');
        }
        output.append(current);
    }
}
