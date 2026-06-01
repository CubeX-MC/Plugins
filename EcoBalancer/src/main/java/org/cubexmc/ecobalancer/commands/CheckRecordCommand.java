package org.cubexmc.ecobalancer.commands;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.util.StringUtil;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

import java.sql.*;
import java.util.*;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;

/*
 * 查看指定操作记录
 * /checkrecord <operation_id> [sort_by] [page]
 * operation_id: 操作ID
 * sort_by: 排序方式，可选值为alphabet（按玩家名称排序）或deduction（按扣除金额排序）
 * page: 页码，可选值为数字
 */
public class CheckRecordCommand implements TabExecutor {
    private final EcoBalancer plugin;

    public CheckRecordCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 进度提示
        sender.sendMessage(plugin.getFormattedMessage("messages.processing", null));
        // 检查参数数量
        if (args.length < 1 || args.length > 3) {
            sender.sendMessage(plugin.getFormattedMessage("messages.record_usage", null));
            return true;
        }

        // 获取要指定的操作ID
        int operationId;
        try {
            operationId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getFormattedMessage("messages.record_invalid_id", null));
            return true;
        }

        int page = 1;
        String sortBy = "deduction"; // 默认排序方式为扣除金额
        if (args.length >= 2) {
            // 检查排序方式
            if (args[1].equalsIgnoreCase("alphabet") || args[1].equalsIgnoreCase("deduction")) {
                sortBy = args[1].toLowerCase();
                // 检查是否指定了页码
                if (args.length == 3) {
                    try {
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.invalid_page", null));
                        return true;
                    }
                }
            } else { //checkrecord <operation_id> [page]
                try {
                    page = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.invalid_page", null));
                    return true;
                }
            }
        }

        final int finalOperationId = operationId;
        final int finalPage = page;
        final String finalSortBy = sortBy;
        SchedulerUtils.runTaskAsync(plugin, () -> loadAndSendRecord(sender, finalOperationId, finalSortBy, finalPage));

        return true;
    }

    private static class RecordDetail {
        String playerName;
        double oldBalance;
        double newBalance;
        double deduction;
        String result;
        String reason;
    }

    private void loadAndSendRecord(CommandSender sender, int operationId, String sortBy, int page) {
        final int pageSize = 10;
        try (Connection connection = DatabaseUtils.getConnection(plugin)) {
            try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM operations WHERE id = ?")) {
                preparedStatement.setInt(1, operationId);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        SchedulerUtils.runTask(plugin,
                                () -> sender.sendMessage(plugin.getFormattedMessage("messages.record_invalid_id", null)));
                        return;
                    }

                    boolean isCheckAll = resultSet.getBoolean("is_checkall");
                    if (isCheckAll) {
                        int offset = Math.max(0, (page - 1) * pageSize);
                        List<RecordDetail> details = new ArrayList<>();
                        String orderBySql = sortBy.equals("alphabet") ? "player_name" : "deduction DESC";
                        try (PreparedStatement selectStatement = connection.prepareStatement(
                                "SELECT * FROM records WHERE operation_id = ? ORDER BY " + orderBySql + " LIMIT ? OFFSET ?")) {
                            selectStatement.setInt(1, operationId);
                            selectStatement.setInt(2, pageSize);
                            selectStatement.setInt(3, offset);
                            try (ResultSet allRecords = selectStatement.executeQuery()) {
                                while (allRecords.next()) {
                                    RecordDetail detail = new RecordDetail();
                                    detail.playerName = allRecords.getString("player_name");
                                    detail.oldBalance = allRecords.getDouble("old_balance");
                                    detail.newBalance = allRecords.getDouble("new_balance");
                                    detail.deduction = allRecords.getDouble("deduction");
                                    detail.result = allRecords.getString("result");
                                    detail.reason = allRecords.getString("reason");
                                    details.add(detail);
                                }
                            }
                        }

                        int total = 0;
                        try (PreparedStatement countStatement = connection
                                .prepareStatement("SELECT COUNT(*) AS total FROM records WHERE operation_id = ?")) {
                            countStatement.setInt(1, operationId);
                            try (ResultSet countResult = countStatement.executeQuery()) {
                                if (countResult.next()) {
                                    total = countResult.getInt("total");
                                }
                            }
                        }
                        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
                        SchedulerUtils.runTask(plugin, () -> renderCheckAllRecord(sender, operationId, sortBy, page, totalPages, details));
                    } else {
                        RecordDetail detail = null;
                        try (PreparedStatement selectStatement = connection.prepareStatement(
                                "SELECT * FROM records WHERE operation_id = ? AND deduction != 0.0")) {
                            selectStatement.setInt(1, operationId);
                            try (ResultSet allRecords = selectStatement.executeQuery()) {
                                if (allRecords.next()) {
                                    detail = new RecordDetail();
                                    detail.playerName = allRecords.getString("player_name");
                                    detail.oldBalance = allRecords.getDouble("old_balance");
                                    detail.newBalance = allRecords.getDouble("new_balance");
                                    detail.deduction = allRecords.getDouble("deduction");
                                    detail.result = allRecords.getString("result");
                                    detail.reason = allRecords.getString("reason");
                                }
                            }
                        }
                        RecordDetail finalDetail = detail;
                        SchedulerUtils.runTask(plugin, () -> renderSinglePlayerRecord(sender, operationId, finalDetail));
                    }
                }
            }
        } catch (SQLException e) {
            Map<String, String> errorPlaceholders = new HashMap<>();
            errorPlaceholders.put("error", e.getMessage() == null ? "unknown" : e.getMessage());
            SchedulerUtils.runTask(plugin,
                    () -> sender.sendMessage(plugin.getFormattedMessage("messages.record_error", errorPlaceholders)));
        }
    }

    private void renderCheckAllRecord(CommandSender sender, int operationId, String sortBy, int page, int totalPages,
            List<RecordDetail> details) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("operation_id", String.valueOf(operationId));
        sender.sendMessage(plugin.getFormattedMessage("messages.record_all_header", placeholders));

        for (RecordDetail detail : details) {
            Map<String, String> detailPlaceholders = new HashMap<>();
            detailPlaceholders.put("player", detail.playerName);
            detailPlaceholders.put("old_balance", String.format("%.2f", detail.oldBalance));
            detailPlaceholders.put("new_balance", String.format("%.2f", detail.newBalance));
            detailPlaceholders.put("deduction", String.format("%.2f", detail.deduction));
            detailPlaceholders.put("result", detail.result == null ? "LEGACY" : detail.result);
            detailPlaceholders.put("reason", detail.reason == null ? "" : detail.reason);
            sender.sendMessage(plugin.getFormattedMessage("messages.record_all_detail", detailPlaceholders));
        }

        Map<String, String> pagePlaceholders = new HashMap<>();
        pagePlaceholders.put("page", String.valueOf(page));
        pagePlaceholders.put("total", String.valueOf(totalPages));
        String baseCmdPrefix = "/ecobal checkrecord " + operationId + " " + sortBy + " ";

        TextComponent prevPageComp = new TextComponent();
        TextComponent nextPageComp = new TextComponent();
        if (page > 1) {
            prevPageComp.setText(plugin.getFormattedMessage("messages.prev_page", null));
            prevPageComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, baseCmdPrefix + (page - 1)));
        } else {
            prevPageComp.setText(plugin.getFormattedMessage("messages.no_prev_page", null));
        }
        if (page < totalPages) {
            nextPageComp.setText(plugin.getFormattedMessage("messages.next_page", null));
            nextPageComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, baseCmdPrefix + (page + 1)));
        } else {
            nextPageComp.setText(plugin.getFormattedMessage("messages.no_next_page", null));
        }

        TextComponent pageMessage = plugin.getFormattedMessage(
                "messages.record_page",
                pagePlaceholders,
                new String[] { "prev", "next" },
                new TextComponent[] { prevPageComp, nextPageComp });
        sender.spigot().sendMessage(pageMessage);
        sender.sendMessage(plugin.getFormattedMessage("messages.record_footer", null));
    }

    private void renderSinglePlayerRecord(CommandSender sender, int operationId, RecordDetail detail) {
        if (detail == null) {
            sender.sendMessage(plugin.getFormattedMessage("messages.record_not_found", null));
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("operation_id", String.valueOf(operationId));
        placeholders.put("player", detail.playerName);
        placeholders.put("old_balance", String.format("%.2f", detail.oldBalance));
        placeholders.put("new_balance", String.format("%.2f", detail.newBalance));
        placeholders.put("deduction", String.format("%.2f", detail.deduction));
        placeholders.put("result", detail.result == null ? "LEGACY" : detail.result);
        placeholders.put("reason", detail.reason == null ? "" : detail.reason);

        sender.sendMessage(plugin.getFormattedMessage("messages.record_player_header", placeholders));
        sender.sendMessage(plugin.getFormattedMessage("messages.record_player_detail", placeholders));
    }

    @Override
    public final List<String> onTabComplete(final CommandSender commandSender, final Command command, final String s, final String[] strings) {
        // Expecting: /ecobal checkrecord <operation_id> [sort_by|page] [page]
        List<String> suggestions = new ArrayList<>();

        // No suggestions for the first arg here (operation_id)
        if (strings.length == 0) return suggestions;

        // If typing the 2nd arg, suggest sort options; also allow numeric page
        if (strings.length == 2) {
            suggestions.add("deduction");
            suggestions.add("alphabet");
            return StringUtil.copyPartialMatches(strings[1], suggestions, new ArrayList<>());
        }

        // If typing the 3rd arg and 2nd arg was a sort keyword, suggest some common pages
        if (strings.length == 3) {
            String arg2 = strings[1].toLowerCase(Locale.ROOT);
            if ("deduction".equals(arg2) || "alphabet".equals(arg2)) {
                suggestions.add("1");
                suggestions.add("2");
                suggestions.add("3");
                return StringUtil.copyPartialMatches(strings[2], suggestions, new ArrayList<>());
            }
        }

        return suggestions;
    }
}
