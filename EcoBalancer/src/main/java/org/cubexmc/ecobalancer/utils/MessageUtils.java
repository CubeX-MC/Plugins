package org.cubexmc.ecobalancer.utils;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * 消息格式化工具类
 */
public class MessageUtils {
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[a-z0-9_:-]+");
    private static final Pattern LEGACY_COLOR_MARKER = Pattern.compile("(?i)(?:&(?:#[0-9a-f]{6}|[0-9a-fk-or])|§[0-9a-fk-or])");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
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
    
    /**
     * 格式化消息，替换占位符
     * @param config 语言配置
     * @param path 配置路径
     * @param placeholders 占位符映射
     * @param prefix 消息前缀
     * @return 格式化后的消息
     */
    public static String formatMessage(FileConfiguration config, String path, Map<String, String> placeholders, String prefix) {
        Map<String, String> map = new HashMap<>();
        if (placeholders != null) {
            map.putAll(placeholders);
        }
        String message = config.getString(path, "Message not found!");
        return renderMiniMessage(message, map, prefix);
    }
    
    /**
     * 创建带有可点击部分的TextComponent
     * @param config 语言配置
     * @param path 配置路径
     * @param placeholders 占位符映射
     * @param clickablePlaceholders 可点击的占位符
     * @param clickableComponents 对应的可点击组件
     * @param prefix 消息前缀
     * @return 格式化后的TextComponent
     */
    public static TextComponent formatComponent(FileConfiguration config, String path, Map<String, String> placeholders, 
                                                String[] clickablePlaceholders, TextComponent[] clickableComponents, String prefix) {
        Map<String, String> map = new HashMap<>();
        if (placeholders != null) {
            map.putAll(placeholders);
        }

        String message = config.getString(path, "Message not found!");

        TextComponent finalMessage = new TextComponent();
        if (clickablePlaceholders != null && clickableComponents != null) {
            int cursor = 0;
            while (cursor < message.length()) {
                ClickableMatch match = nextClickable(message, cursor, clickablePlaceholders);
                if (match == null) {
                    finalMessage.addExtra(new TextComponent(renderMiniMessage(message.substring(cursor), map, prefix)));
                    break;
                }
                if (match.start > cursor) {
                    finalMessage.addExtra(new TextComponent(renderMiniMessage(message.substring(cursor, match.start), map, prefix)));
                }
                finalMessage.addExtra(clickableComponents[match.index]);
                cursor = match.end;
            }
        } else {
            finalMessage = new TextComponent(renderMiniMessage(message, map, prefix));
        }
        
        return finalMessage;
    }

    public static String renderMiniMessage(String template, Map<String, String> placeholders, String legacyPrefix) {
        String message = template == null ? "" : template;
        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(Placeholder.component("prefix", legacyComponent(legacyPrefix)));
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String name = entry.getKey();
                if (name == null) {
                    continue;
                }
                String normalized = name.toLowerCase(java.util.Locale.ROOT);
                if (!PLACEHOLDER_NAME.matcher(normalized).matches()) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (containsLegacyStyle(value)) {
                    String miniMessageValue = legacyTextToMiniMessage(value);
                    message = message.replace("<" + normalized + ">", miniMessageValue);
                    message = message.replace("%" + name + "%", miniMessageValue);
                } else {
                    builder.resolver(Placeholder.unparsed(normalized, value));
                }
            }
        }
        Component component = MINI_MESSAGE.deserialize(message, builder.build());
        return LEGACY_SERIALIZER.serialize(component);
    }

    private static Component legacyComponent(String value) {
        return LEGACY_SERIALIZER.deserialize(ChatColor.translateAlternateColorCodes('&', value == null ? "" : value));
    }

    private static boolean containsLegacyStyle(String value) {
        return value != null && LEGACY_COLOR_MARKER.matcher(value).find();
    }

    private static String legacyTextToMiniMessage(String value) {
        String input = value == null ? "" : value.replace('§', '&');
        StringBuilder output = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '&' && index + 1 < input.length()) {
                if (input.charAt(index + 1) == '#'
                        && index + 7 < input.length()
                        && isHex(input, index + 2, index + 8)) {
                    output.append("<#").append(input, index + 2, index + 8).append('>');
                    index += 7;
                    continue;
                }
                String tag = LEGACY_TAGS.get(Character.toLowerCase(input.charAt(index + 1)));
                if (tag != null) {
                    output.append('<').append(tag).append('>');
                    index++;
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

    private static ClickableMatch nextClickable(String message, int start, String[] clickablePlaceholders) {
        ClickableMatch best = null;
        for (int i = 0; i < clickablePlaceholders.length; i++) {
            String name = clickablePlaceholders[i];
            if (name == null || name.isBlank()) {
                continue;
            }
            String tagToken = "<" + name.toLowerCase(Locale.ROOT) + ">";
            int tagIndex = message.indexOf(tagToken, start);
            if (tagIndex >= 0 && (best == null || tagIndex < best.start)) {
                best = new ClickableMatch(tagIndex, tagIndex + tagToken.length(), i);
            }
            String legacyToken = "%" + name + "%";
            int legacyIndex = message.indexOf(legacyToken, start);
            if (legacyIndex >= 0 && (best == null || legacyIndex < best.start)) {
                best = new ClickableMatch(legacyIndex, legacyIndex + legacyToken.length(), i);
            }
        }
        return best;
    }

    private static final class ClickableMatch {
        private final int start;
        private final int end;
        private final int index;

        private ClickableMatch(int start, int end, int index) {
            this.start = start;
            this.end = end;
            this.index = index;
        }
    }
    
    /**
     * 发送消息给CommandSender并可选择记录到日志
     * @param sender 命令发送者
     * @param message 消息内容
     * @param logger 日志器
     * @param isLog 是否记录到日志
     */
    public static void sendMessage(CommandSender sender, String message, Logger logger, boolean isLog) {
        if (sender != null) {
            for (String str : message.split("\n")) {
                sender.sendMessage(str);
            }
        }
        if (isLog && logger != null) {
            for (String str : message.split("\n")) {
                logger.info(str);
            }
        }
    }
    
    /**
     * 创建一个带点击和悬停事件的TextComponent
     * @param text 文本内容
     * @param clickAction 点击动作
     * @param clickValue 点击值
     * @param hoverText 悬停文本
     * @return 格式化后的TextComponent
     */
    public static TextComponent createClickableComponent(String text, ClickEvent.Action clickAction, String clickValue, String hoverText) {
        TextComponent component = new TextComponent(text);
        component.setClickEvent(new ClickEvent(clickAction, clickValue));
        if (hoverText != null && !hoverText.isEmpty()) {
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(hoverText).create()));
        }
        return component;
    }
}
