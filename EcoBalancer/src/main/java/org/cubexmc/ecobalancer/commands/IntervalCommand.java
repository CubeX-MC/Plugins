package org.cubexmc.ecobalancer.commands;

// removed unused ClickEvent/TextComponent imports
// removed unused Bukkit import
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
// removed unused FileConfiguration import
import org.bukkit.util.StringUtil;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.PageUtils;
import org.cubexmc.ecobalancer.utils.VaultUtils;
import org.cubexmc.ecobalancer.utils.AnalysisFilters;

import java.util.*;

public class IntervalCommand implements TabExecutor {
    private final EcoBalancer plugin;

    public IntervalCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // New filter-style parsing: tokens d: p: l: u: lr: ur: + optional [alphabet|balance] and [page]
        AnalysisFilters.ParseResult pr = AnalysisFilters.parse(args);
        String sortBy = "alphabet";
        int page = 1;
        // Remaining args: [sortBy] [page]
        if (!pr.remainingArgs.isEmpty()) {
            String a0 = pr.remainingArgs.get(0);
            if ("alphabet".equalsIgnoreCase(a0) || "balance".equalsIgnoreCase(a0)) {
                sortBy = a0.toLowerCase();
                if (pr.remainingArgs.size() > 1) {
                    try { page = Integer.parseInt(pr.remainingArgs.get(1)); }
                    catch (NumberFormatException e) {
                        sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.invalid_page", null, plugin.getMessagePrefix()));
                        return true;
                    }
                }
            } else {
                try { page = Integer.parseInt(a0); }
                catch (NumberFormatException ignored) {}
            }
        }

        // 收集符合条件的玩家
        List<OfflinePlayer> matchedPlayers = AnalysisFilters.collectFilteredPlayers(pr.criteria, plugin.getConfig().getString("stats-world", ""));

        // 排序玩家列表
        sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.interval_sorting", null, plugin.getMessagePrefix()));
        if (sortBy.equals("balance")) {
            matchedPlayers.sort((p1, p2) -> Double.compare(VaultUtils.getBalance(p2), VaultUtils.getBalance(p1)));
        } else {
            matchedPlayers.sort(Comparator.comparing((OfflinePlayer p) -> Optional.ofNullable(p.getName()).orElse(""), String.CASE_INSENSITIVE_ORDER));
        }

        // 处理分页显示
        final int pageSize = 10; // 每页显示10个玩家
        int totalPages = PageUtils.calculateTotalPages(matchedPlayers.size(), pageSize);

        if (!PageUtils.isValidPage(page, totalPages)) {
            sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.invalid_page", null, plugin.getMessagePrefix()));
            return true;
        }

        // 显示页头
        Map<String, String> headerPlaceholders = new HashMap<>();
        double low = pr.criteria.minBalance == null ? Double.NEGATIVE_INFINITY : pr.criteria.minBalance;
        double up = pr.criteria.maxBalance == null ? Double.POSITIVE_INFINITY : pr.criteria.maxBalance;
        headerPlaceholders.put("low", low == Double.NEGATIVE_INFINITY ? "∞" : String.format("%.2f", low));
        headerPlaceholders.put("up", up == Double.POSITIVE_INFINITY ? "∞" : String.format("%.2f", up));
        sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.interval_header", headerPlaceholders, plugin.getMessagePrefix()));

        // 使用PageUtils渲染玩家列表（保留筛选 token，便于翻页）
        StringBuilder cmdFmt = new StringBuilder("/interval");
        for (String tok : args) { if (tok.contains(":")) cmdFmt.append(' ').append(tok); }
        cmdFmt.append(' ').append(sortBy).append(' ').append("%d");
        final String commandFormat = cmdFmt.toString();
        // finalLow/finalUp no longer needed after header formatting
        
        PageUtils.renderPagination(
            sender,
            matchedPlayers,
            pageSize,
            page,
            (s, player, i) -> {
                // 渲染单个玩家
                double balance = VaultUtils.getBalance(player);
                long lastPlayed = player.getLastPlayed();
                long currentTime = System.currentTimeMillis();
                long daysOffline = (currentTime - lastPlayed) / (1000 * 60 * 60 * 24);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName() != null ? player.getName() : "Unknown");
                placeholders.put("balance", String.format("%.2f", balance));
                placeholders.put("days_offline", String.valueOf(daysOffline));

                s.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.interval_player", placeholders, plugin.getMessagePrefix()));
            },
            "messages.interval_header",
            "messages.interval_footer",
            "messages.interval_page",
            commandFormat,
            plugin.getLangConfig(),
            "messages.invalid_page",
            plugin.getMessagePrefix(),
            headerPlaceholders
        );

        return true;
    }

    @Override
    public final List<String> onTabComplete(final CommandSender commandSender, final Command command, final String s, final String[] strings) {
        final int size = 2;
        final Collection<String> ret = new ArrayList<>(size);
        if (1 == strings.length) {
            ret.add("alphabet");
            ret.add("balance");
        }
        final String lowerCase = strings[0].toLowerCase(Locale.ROOT);
        return StringUtil.copyPartialMatches(lowerCase, ret, new ArrayList<>(size));
    }
}
