package org.cubexmc.ecobalancer.utils;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分页显示工具类
 */
public class PageUtils {
    
    /**
     * 计算总页数
     * @param totalItems 总条目数
     * @param pageSize 每页显示条目数
     * @return 总页数
     */
    public static int calculateTotalPages(int totalItems, int pageSize) {
        return (totalItems + pageSize - 1) / pageSize;
    }
    
    /**
     * 验证页码是否有效
     * @param page 当前页码
     * @param totalPages 总页数
     * @return 页码是否有效
     */
    public static boolean isValidPage(int page, int totalPages) {
        return page >= 1 && page <= totalPages;
    }
    
    /**
     * 计算页面起始索引
     * @param page 页码
     * @param pageSize 每页显示条目数
     * @return 起始索引
     */
    public static int getStartIndex(int page, int pageSize) {
        return (page - 1) * pageSize;
    }
    
    /**
     * 计算页面结束索引
     * @param page 页码
     * @param pageSize 每页显示条目数
     * @param totalItems 总条目数
     * @return 结束索引
     */
    public static int getEndIndex(int page, int pageSize, int totalItems) {
        return Math.min(getStartIndex(page, pageSize) + pageSize, totalItems);
    }
    
    /**
     * 创建分页导航组件
     * @param langConfig 语言配置
     * @param currentPage 当前页码
     * @param totalPages 总页数
     * @param commandFormat 命令格式，使用%d作为页码占位符
     * @param prefix 消息前缀
     * @return 导航组件
     */
    public static TextComponent createPageNavigation(FileConfiguration langConfig, int currentPage, int totalPages, String commandFormat, String prefix) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("page", String.valueOf(currentPage));
        placeholders.put("total", String.valueOf(totalPages));
        
        // 创建上一页和下一页按钮
        TextComponent prevPage = new TextComponent();
        TextComponent nextPage = new TextComponent();
        
        if (currentPage > 1) {
            prevPage.setText(MessageUtils.formatMessage(langConfig, "messages.prev_page", null, prefix));
            prevPage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, 
                    String.format(commandFormat, currentPage - 1)));
        } else {
            prevPage.setText(MessageUtils.formatMessage(langConfig, "messages.no_prev_page", null, prefix));
        }
        
        if (currentPage < totalPages) {
            nextPage.setText(MessageUtils.formatMessage(langConfig, "messages.next_page", null, prefix));
            nextPage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, 
                    String.format(commandFormat, currentPage + 1)));
        } else {
            nextPage.setText(MessageUtils.formatMessage(langConfig, "messages.no_next_page", null, prefix));
        }
        
        placeholders.put("prev", prevPage.toPlainText());
        placeholders.put("next", nextPage.toPlainText());
        
        return MessageUtils.formatComponent(
                langConfig, 
                "messages.page_navigation", 
                placeholders, 
                new String[]{"prev", "next"}, 
                new TextComponent[]{prevPage, nextPage},
                prefix
        );
    }
    
    /**
     * 处理分页显示逻辑
     * @param sender 命令发送者
     * @param items 要显示的条目列表
     * @param pageSize 每页显示条目数
     * @param currentPage 当前页码
     * @param renderer 条目渲染器接口
     * @param headerMessagePath 页头消息路径
     * @param footerMessagePath 页脚消息路径
     * @param navigationMessagePath 导航消息路径
     * @param commandFormat 命令格式
     * @param langConfig 语言配置
     * @param invalidPageMessagePath 无效页码消息路径
     * @param prefix 消息前缀
     * @param <T> 条目类型
     */
    public static <T> void renderPagination(
            CommandSender sender,
            List<T> items, 
            int pageSize, 
            int currentPage, 
            ItemRenderer<T> renderer,
            String headerMessagePath,
            String footerMessagePath,
            String navigationMessagePath,
            String commandFormat,
            FileConfiguration langConfig,
            String invalidPageMessagePath,
            String prefix,
            Map<String, String> extraPlaceholders) {
        
        int totalPages = calculateTotalPages(items.size(), pageSize);
        
        if (!isValidPage(currentPage, totalPages)) {
            sender.sendMessage(MessageUtils.formatMessage(langConfig, invalidPageMessagePath, null, prefix));
            return;
        }
        
        int start = getStartIndex(currentPage, pageSize);
        int end = getEndIndex(currentPage, pageSize, items.size());
        
        // 发送页头
        if (headerMessagePath != null) {
            Map<String, String> headerPlaceholders = new HashMap<>();
            if (extraPlaceholders != null) {
                headerPlaceholders.putAll(extraPlaceholders);
            }
            sender.sendMessage(MessageUtils.formatMessage(langConfig, headerMessagePath, headerPlaceholders, prefix));
        }
        
        // 渲染条目
        for (int i = start; i < end; i++) {
            T item = items.get(i);
            renderer.render(sender, item, i);
        }
        
        // 发送导航组件
        if (navigationMessagePath != null) {
            TextComponent navigationComponent = createPageNavigation(langConfig, currentPage, totalPages, commandFormat, prefix);
            sender.spigot().sendMessage(navigationComponent);
        }
        
        // 发送页脚
        if (footerMessagePath != null) {
            sender.sendMessage(MessageUtils.formatMessage(langConfig, footerMessagePath, null, prefix));
        }
    }
    
    /**
     * 条目渲染器接口
     * @param <T> 条目类型
     */
    public interface ItemRenderer<T> {
        /**
         * 渲染单个条目
         * @param sender 命令发送者
         * @param item 条目
         * @param index 条目索引
         */
        void render(CommandSender sender, T item, int index);
    }
} 