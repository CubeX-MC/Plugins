package org.cubexmc.ecobalancer.commands;

import net.md_5.bungee.api.chat.ClickEvent;
// removed unused ComponentBuilder/HoverEvent imports
import net.md_5.bungee.api.chat.TextComponent;
// removed unused Bukkit/OfflinePlayer imports
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.DatabaseUtils;
import org.cubexmc.ecobalancer.utils.MessageUtils;
import org.cubexmc.ecobalancer.utils.PageUtils;
import org.cubexmc.ecobalancer.utils.SchedulerUtils;

// removed unused File import
import java.sql.*;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.*;

public class CheckRecordsCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public CheckRecordsCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int pageNumber = 1;
        final int pageSize = 10;

        // 解析页码参数
        if (args.length > 0) {
            try {
                pageNumber = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(MessageUtils.formatMessage(plugin.getLangConfig(), "messages.invalid_page", null, plugin.getMessagePrefix()));
                return true;
            }
        }

        // 进度提示
        sender.sendMessage(plugin.getFormattedMessage("messages.processing", null));
        final int finalPageNumber = pageNumber;
        SchedulerUtils.runTaskAsync(plugin, () -> loadAndSendRecords(sender, pageSize, finalPageNumber));

        return true;
    }

    private void loadAndSendRecords(CommandSender sender, int pageSize, int pageNumber) {
        try (Connection connection = DatabaseUtils.getConnection(plugin)) {
            List<OperationRecord> operations = fetchOperations(connection, pageSize, pageNumber);
            int totalRecords = getTotalOperationsCount(connection);
            int totalPages = PageUtils.calculateTotalPages(totalRecords, pageSize);
            SchedulerUtils.runTask(plugin, () -> renderOperations(sender, operations, pageNumber, totalPages));
        } catch (SQLException e) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("error", e.getMessage() == null ? "unknown" : e.getMessage());
            SchedulerUtils.runTask(plugin, () -> sender.sendMessage(
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.records_error", placeholders,
                            plugin.getMessagePrefix())));
        }
    }

    private void renderOperations(CommandSender sender, List<OperationRecord> operations, int pageNumber, int totalPages) {
        if (operations.isEmpty()) {
            sender.sendMessage(
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.no_records", null, plugin.getMessagePrefix()));
            return;
        }

        sender.sendMessage(
                MessageUtils.formatMessage(plugin.getLangConfig(), "messages.records_header", null, plugin.getMessagePrefix()));
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (OperationRecord operation : operations) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("time", dateFormat.format(new Date(operation.timestamp)));
            placeholders.put("type", operation.isCheckAll ? "A" : "P");
            placeholders.put("deduction_amount", String.format("%.2f", operation.totalDeduction));
            placeholders.put("operation_id", String.valueOf(operation.id));
            placeholders.put("restored", operation.isRestored ? "x" : " ");

            TextComponent operationIdComponent = MessageUtils.createClickableComponent(
                    String.valueOf(operation.id),
                    ClickEvent.Action.RUN_COMMAND,
                    "/ecobal checkrecord " + operation.id,
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.records_click", null, plugin.getMessagePrefix()));

            TextComponent messageFormat = MessageUtils.formatComponent(
                    plugin.getLangConfig(),
                    "messages.records_operation",
                    placeholders,
                    new String[] { "operation_id" },
                    new TextComponent[] { operationIdComponent },
                    plugin.getMessagePrefix());
            sender.spigot().sendMessage(messageFormat);
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("page", String.valueOf(pageNumber));
        placeholders.put("total", String.valueOf(totalPages));

        TextComponent prevPage = new TextComponent();
        TextComponent nextPage = new TextComponent();
        if (pageNumber > 1) {
            prevPage.setText(
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.prev_page", null, plugin.getMessagePrefix()));
            prevPage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ecobal checkrecords " + (pageNumber - 1)));
        } else {
            prevPage.setText(
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.no_prev_page", null, plugin.getMessagePrefix()));
        }
        if (pageNumber < totalPages) {
            nextPage.setText(
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.next_page", null, plugin.getMessagePrefix()));
            nextPage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ecobal checkrecords " + (pageNumber + 1)));
        } else {
            nextPage.setText(
                    MessageUtils.formatMessage(plugin.getLangConfig(), "messages.no_next_page", null, plugin.getMessagePrefix()));
        }
        placeholders.put("prev", prevPage.toPlainText());
        placeholders.put("next", nextPage.toPlainText());

        TextComponent message = MessageUtils.formatComponent(
                plugin.getLangConfig(),
                "messages.records_page",
                placeholders,
                new String[] { "prev", "next" },
                new TextComponent[] { prevPage, nextPage },
                plugin.getMessagePrefix());

        sender.spigot().sendMessage(message);
        sender.sendMessage(
                MessageUtils.formatMessage(plugin.getLangConfig(), "messages.records_footer", null, plugin.getMessagePrefix()));
    }
    
    /**
     * 从数据库获取操作记录
     */
    private List<OperationRecord> fetchOperations(Connection connection, int pageSize, int pageNumber) throws SQLException {
        List<OperationRecord> operations = new ArrayList<>();
        int offset = (pageNumber - 1) * pageSize;
        
        String sql = "SELECT o.id, o.timestamp, o.is_restored, r.is_checkall, SUM(r.deduction) AS total_deduction "
                + "FROM operations o JOIN records r ON o.id = r.operation_id "
                + "GROUP BY o.id ORDER BY o.timestamp DESC LIMIT ? OFFSET ?";
                
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setInt(1, pageSize);
            preparedStatement.setInt(2, offset);
            
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    OperationRecord record = new OperationRecord();
                    record.id = resultSet.getInt("id");
                    record.timestamp = resultSet.getLong("timestamp");
                    record.isCheckAll = resultSet.getBoolean("is_checkall");
                    record.totalDeduction = resultSet.getDouble("total_deduction");
                    record.isRestored = resultSet.getBoolean("is_restored");
                    operations.add(record);
                }
            }
        }
        
        return operations;
    }
    
    /**
     * 获取总记录数
     */
    private int getTotalOperationsCount(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) AS total FROM (SELECT o.id FROM operations o JOIN records r ON o.id = r.operation_id GROUP BY o.id)";
        
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return resultSet.getInt("total");
            }
        }
        
        return 0;
    }
    
    /**
     * 操作记录对象
     */
    private static class OperationRecord {
        int id;
        long timestamp;
        boolean isCheckAll;
        boolean isRestored;
        double totalDeduction;
    }
}