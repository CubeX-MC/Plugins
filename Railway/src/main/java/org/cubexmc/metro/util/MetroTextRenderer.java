package org.cubexmc.metro.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class MetroTextRenderer {

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[a-z0-9_:-]+");
    private static final Pattern UNRESOLVED_TAG = Pattern.compile("(?<!\\\\)<([a-z0-9_:-]+)>");
    private static final Pattern LEGACY_MARKER = Pattern.compile("(?i)(?:&(?:#[0-9a-f]{6}|[0-9a-fk-or])|§[0-9a-fk-or])");
    private static final Set<String> MINIMESSAGE_TAGS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "obfuscated", "bold", "strikethrough", "underlined", "italic", "reset");
    private static final Set<String> TRUSTED_FORMATTED_PLACEHOLDERS = Set.of(
            "line_color_code", "color_code", "status", "state", "routes", "title", "subtitle");

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private MetroTextRenderer() {
    }

    public static String render(String template) {
        return render(template, Map.of());
    }

    public static String render(String template, Map<String, ?> placeholders) {
        String normalized = normalizeLegacyMarkers(template == null ? "" : template);
        TagResolver resolver = resolver(placeholders);
        Component component = MINI_MESSAGE.deserialize(normalized, resolver);
        return LEGACY_SERIALIZER.serialize(component);
    }

    public static String renderPreservingPlaceholders(String template) {
        String normalized = normalizeLegacyMarkers(template == null ? "" : template);
        normalized = preserveUnresolvedPlaceholders(normalized);
        Component component = MINI_MESSAGE.deserialize(normalized);
        return LEGACY_SERIALIZER.serialize(component);
    }

    public static String convertLegacyTemplate(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        StringBuilder output = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '&' || current == '§') {
                int consumed = appendLegacyTag(input, index, current, output);
                if (consumed > index) {
                    index = consumed;
                    continue;
                }
            }
            if (current == '{') {
                int end = input.indexOf('}', index + 1);
                if (end > index) {
                    String name = input.substring(index + 1, end).toLowerCase(Locale.ROOT);
                    if (PLACEHOLDER_NAME.matcher(name).matches()) {
                        output.append('<').append(name).append('>');
                        index = end;
                        continue;
                    }
                }
            }
            if (current == '%') {
                int consumed = appendPercentPlaceholder(input, index, output);
                if (consumed > index) {
                    index = consumed;
                    continue;
                }
            }
            if (current == '<') {
                output.append('\\');
            }
            output.append(current);
        }
        return output.toString();
    }

    public static boolean hasLegacyMarker(String value) {
        return value != null && LEGACY_MARKER.matcher(value).find();
    }

    private static TagResolver resolver(Map<String, ?> placeholders) {
        TagResolver.Builder builder = TagResolver.builder();
        if (placeholders == null || placeholders.isEmpty()) {
            return builder.build();
        }
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            String name = normalizePlaceholderName(entry.getKey());
            if (name == null) {
                continue;
            }
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (TRUSTED_FORMATTED_PLACEHOLDERS.contains(name)) {
                builder.resolver(Placeholder.parsed(name, normalizeLegacyMarkers(value)));
            } else {
                builder.resolver(Placeholder.unparsed(name, value));
            }
        }
        return builder.build();
    }

    private static String normalizePlaceholderName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_NAME.matcher(normalized).matches() ? normalized : null;
    }

    private static String preserveUnresolvedPlaceholders(String template) {
        Matcher matcher = UNRESOLVED_TAG.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (isMiniMessageTag(name)) {
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("{" + name + "}"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isMiniMessageTag(String name) {
        return name != null && (MINIMESSAGE_TAGS.contains(name) || name.startsWith("#"));
    }

    private static String normalizeLegacyMarkers(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '&' || current == '§') {
                int consumed = appendLegacyTag(value, index, current, output);
                if (consumed > index) {
                    index = consumed;
                    continue;
                }
            }
            output.append(current);
        }
        return output.toString();
    }

    private static int appendPercentPlaceholder(String input, int percentIndex, StringBuilder output) {
        int next = percentIndex + 1;
        if (next >= input.length()) {
            return percentIndex;
        }
        if (Character.isDigit(input.charAt(next))) {
            int end = next;
            while (end < input.length() && Character.isDigit(input.charAt(end))) {
                end++;
            }
            output.append("<arg").append(input, next, end).append('>');
            return end - 1;
        }
        int closing = input.indexOf('%', next);
        if (closing > percentIndex) {
            String name = input.substring(next, closing).toLowerCase(Locale.ROOT);
            if (PLACEHOLDER_NAME.matcher(name).matches()) {
                output.append('<').append(name).append('>');
                return closing;
            }
        }
        return percentIndex;
    }

    private static int appendLegacyTag(String input, int markerIndex, char marker, StringBuilder output) {
        if (markerIndex + 1 >= input.length()) {
            return markerIndex;
        }
        if (marker == '&'
                && input.charAt(markerIndex + 1) == '#'
                && markerIndex + 7 < input.length()
                && isHex(input, markerIndex + 2, markerIndex + 8)) {
            output.append("<#").append(input, markerIndex + 2, markerIndex + 8).append('>');
            return markerIndex + 7;
        }
        char code = Character.toLowerCase(input.charAt(markerIndex + 1));
        String tag = switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            case 'r' -> "reset";
            default -> null;
        };
        if (tag == null) {
            return markerIndex;
        }
        output.append('<').append(tag).append('>');
        return markerIndex + 1;
    }

    private static boolean isHex(String input, int startInclusive, int endExclusive) {
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
}
