package org.cubexmc.ecobalancer.utils;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 消息格式化工具类
 */
public class MessageUtils {
    
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
        map.put("prefix", prefix);
        
        String message = config.getString(path, "Message not found!");
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            message = message.replace("%" + entry.getKey() + "%", value);
        }
        
        return ChatColor.translateAlternateColorCodes('&', message);
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
        map.put("prefix", prefix);
        
        String message = config.getString(path, "Message not found!");
        
        // 处理占位符
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (clickablePlaceholders != null) {
                boolean isClickable = false;
                for (String clickable : clickablePlaceholders) {
                    if (entry.getKey().equals(clickable)) {
                        isClickable = true;
                        break;
                    }
                }
                if (!isClickable) {
                    String value = entry.getValue() == null ? "" : entry.getValue();
                    message = message.replace("%" + entry.getKey() + "%", value);
                }
            } else {
                String value = entry.getValue() == null ? "" : entry.getValue();
                message = message.replace("%" + entry.getKey() + "%", value);
            }
        }
        
        TextComponent finalMessage = new TextComponent();
        if (clickablePlaceholders != null && clickableComponents != null) {
            String[] parts = message.split("%");
            
            for (int i = 0; i < parts.length; i++) {
                if (i % 2 == 1 && i < parts.length - 1) {  // 奇数索引表示这是一个占位符名称
                    boolean found = false;
                    for (int j = 0; j < clickablePlaceholders.length; j++) {
                        if (parts[i].equals(clickablePlaceholders[j])) {
                            finalMessage.addExtra(clickableComponents[j]);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        finalMessage.addExtra(new TextComponent(ChatColor.translateAlternateColorCodes('&', "%" + parts[i] + "%")));
                    }
                } else {
                    finalMessage.addExtra(new TextComponent(ChatColor.translateAlternateColorCodes('&', parts[i])));
                }
            }
        } else {
            finalMessage = new TextComponent(ChatColor.translateAlternateColorCodes('&', message));
        }
        
        return finalMessage;
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