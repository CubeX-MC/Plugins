package org.cubexmc.fawereplace.commands;

import org.cubexmc.fawereplace.FAWEReplace;
import org.cubexmc.fawereplace.LanguageManager;
import org.cubexmc.fawereplace.tasks.CleaningTask;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 处理 /fawereplace 命令的执行逻辑
 */
public class FaweReplaceCommand implements CommandExecutor {

    private final FAWEReplace plugin;
    private final CleaningTask cleaningTask;
    private final LanguageManager lang;

    public FaweReplaceCommand(FAWEReplace plugin, CleaningTask cleaningTask) {
        this.plugin = plugin;
        this.cleaningTask = cleaningTask;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 权限检查
        if (!sender.hasPermission("fawereplace.use")) {
            sender.sendMessage(lang.getMessage("no_permission"));
            return true;
        }

        // 参数检查
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        // 处理子命令
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start":
                handleStart(sender, args);
                return true;
            case "stop":
                handleStop(sender);
                return true;
            case "status":
                handleStatus(sender);
                return true;
            case "reload":
                handleReload(sender);
                return true;
            case "help":
                sendHelp(sender, label);
                return true;
            case "setregion":
                handleSetRegion(sender);
                return true;
            case "addrule":
                handleAddRule(sender, args);
                return true;
            case "removerule":
                handleRemoveRule(sender, args);
                return true;
            case "rules":
                handleRules(sender);
                return true;
            default:
                sendUsage(sender, label);
                return true;
        }
    }

    /**
     * 处理 start 子命令
     */
    private void handleStart(CommandSender sender, String[] args) {
        // 检查世界是否已正确配置
        if (!plugin.isWorldConfigured()) {
            sender.sendMessage(lang.getMessage("start.world_not_configured", "world", plugin.getConfiguredWorldName()));
            sender.sendMessage(lang.getMessage("start.check_config"));
            return;
        }

        if (cleaningTask.isRunning()) {
            sender.sendMessage(lang.getMessage("start.already_running"));
            return;
        }

        // 检查是否有 --fresh 参数，强制重新开始
        boolean forceRestart = false;
        if (args.length > 1 && args[1].equalsIgnoreCase("--fresh")) {
            forceRestart = true;
        }

        cleaningTask.start(sender, forceRestart);
    }

    /**
     * 处理 stop 子命令
     */
    private void handleStop(CommandSender sender) {
        if (!cleaningTask.isRunning()) {
            sender.sendMessage(lang.getMessage("stop.not_running"));
            return;
        }

        cleaningTask.stop(sender);
        sender.sendMessage(lang.getMessage("stop.stopping"));
    }

    /**
     * 处理 status 子命令
     */
    private void handleStatus(CommandSender sender) {
        // 显示世界配置状态
        if (!plugin.isWorldConfigured()) {
            sender.sendMessage(lang.getMessage("status.world_status", "world", plugin.getConfiguredWorldName()));
            sender.sendMessage(lang.getMessage("status.reload_suggestion"));
            sender.sendMessage("");
        }

        cleaningTask.sendStatus(sender);
    }

    /**
     * 处理 reload 子命令
     */
    private void handleReload(CommandSender sender) {
        if (cleaningTask.isRunning()) {
            sender.sendMessage(lang.getMessage("reload.cannot_while_running"));
            return;
        }

        sender.sendMessage(lang.getMessage("reload.reloading"));

        // 调用主插件的配置重载方法
        if (plugin.reloadConfiguration()) {
            sender.sendMessage(lang.getMessage("reload.success"));
            sender.sendMessage(lang.getMessage("reload.world_found", "world", plugin.getConfiguredWorldName()));
            sender.sendMessage(lang.getMessage("reload.language_changed", "language", lang.getCurrentLanguage()));
            sender.sendMessage(lang.getMessage("reload.apply_next_start"));
        } else {
            sender.sendMessage(lang.getMessage("reload.failed"));
            if (!plugin.isWorldConfigured()) {
                sender.sendMessage(lang.getMessage("reload.world_not_found", "world", plugin.getConfiguredWorldName()));
                sender.sendMessage(lang.getMessage("reload.check_world_name"));
            } else {
                sender.sendMessage(lang.getMessage("reload.check_console"));
            }
        }
    }

    /**
     * 处理 setregion 子命令
     */
    private void handleSetRegion(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.getMessage("player_only"));
            return;
        }

        Player player = (Player) sender;
        try {
            com.sk89q.worldedit.LocalSession session = WorldEdit.getInstance().getSessionManager()
                    .get(BukkitAdapter.adapt(player));
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(player.getWorld());
            Region region = session.getSelection(weWorld);

            if (region == null) {
                sender.sendMessage(lang.getMessage("setregion.no_selection"));
                return;
            }

            // 获取选区坐标
            int minX = region.getMinimumPoint().getBlockX();
            int minY = region.getMinimumPoint().getBlockY();
            int minZ = region.getMinimumPoint().getBlockZ();
            int maxX = region.getMaximumPoint().getBlockX();
            int maxY = region.getMaximumPoint().getBlockY();
            int maxZ = region.getMaximumPoint().getBlockZ();

            // 更新配置 (写入 rules.yml)
            plugin.getRulesConfig().set("world", player.getWorld().getName());
            plugin.getRulesConfig().set("target.start.x", minX);
            plugin.getRulesConfig().set("target.start.y", minY);
            plugin.getRulesConfig().set("target.start.z", minZ);
            plugin.getRulesConfig().set("target.end.x", maxX);
            plugin.getRulesConfig().set("target.end.y", maxY);
            plugin.getRulesConfig().set("target.end.z", maxZ);

            plugin.saveRulesConfig();
            plugin.reloadConfiguration(); // 立即应用更改

            sender.sendMessage(lang.getMessage("setregion.success"));
            sender.sendMessage(lang.getMessage("setregion.coords",
                    "x1", String.valueOf(minX), "y1", String.valueOf(minY), "z1", String.valueOf(minZ),
                    "x2", String.valueOf(maxX), "y2", String.valueOf(maxY), "z2", String.valueOf(maxZ)));
            sender.sendMessage(lang.getMessage("setregion.saved"));

        } catch (IncompleteRegionException e) {
            sender.sendMessage(lang.getMessage("setregion.no_selection"));
        } catch (Exception e) {
            sender.sendMessage(lang.getMessage("error.file_error", "error", e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * 处理 addrule 子命令
     */
    private void handleAddRule(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(lang.getMessage("rule.add_usage"));
            return;
        }

        String origin = args[1].toUpperCase(Locale.ROOT);
        String target = args[2].toUpperCase(Locale.ROOT);

        // 检查是否为实体类型
        org.bukkit.entity.EntityType entityType = null;
        try {
            entityType = org.bukkit.entity.EntityType.valueOf(origin);
        } catch (IllegalArgumentException ignored) {
        }

        if (Material.getMaterial(origin) == null && entityType == null) {
            sender.sendMessage(lang.getMessage("rule.invalid_type", "type", origin));
            return;
        }

        // 如果是实体，目标必须是 AIR
        if (entityType != null) {
            if (!target.equals("AIR")) {
                sender.sendMessage(lang.getMessage("rule.entity_target_error"));
                return;
            }

            // 添加实体规则
            List<String> entities = plugin.getRulesConfig().getStringList("entities");
            if (entities == null)
                entities = new ArrayList<>();
            if (!entities.contains(origin)) {
                entities.add(origin);
                plugin.getRulesConfig().set("entities", entities);
                plugin.saveRulesConfig();
                plugin.reloadConfiguration();
            }

            sender.sendMessage(lang.getMessage("rule.add_success", "origin", origin, "target", "AIR (Entity)"));
            sender.sendMessage(lang.getMessage("setregion.saved"));
            return;
        }

        if (Material.getMaterial(target) == null && !target.equals("AIR")) {
            sender.sendMessage(lang.getMessage("rule.invalid_material", "material", target));
            return;
        }

        // 获取当前规则列表 (从 rules.yml)
        List<Map<?, ?>> blocks = plugin.getRulesConfig().getMapList("blocks");
        if (blocks == null)
            blocks = new ArrayList<>();

        // 检查是否已存在（如果存在则更新）
        boolean found = false;
        List<Map<String, Object>> newBlocks = new ArrayList<>();
        for (Map<?, ?> map : blocks) {
            Map<String, Object> newMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                newMap.put(entry.getKey().toString(), entry.getValue());
            }

            if (newMap.get("origin").toString().equals(origin)) {
                newMap.put("target", target);
                found = true;
            }
            newBlocks.add(newMap);
        }

        if (!found) {
            Map<String, Object> newRule = new HashMap<>();
            newRule.put("origin", origin);
            newRule.put("target", target);
            newBlocks.add(newRule);
        }

        plugin.getRulesConfig().set("blocks", newBlocks);
        plugin.saveRulesConfig();
        plugin.reloadConfiguration();

        sender.sendMessage(lang.getMessage("rule.add_success", "origin", origin, "target", target));
        sender.sendMessage(lang.getMessage("setregion.saved"));
    }

    /**
     * 处理 removerule 子命令
     */
    private void handleRemoveRule(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.getMessage("rule.remove_usage"));
            return;
        }

        String origin = args[1].toUpperCase(Locale.ROOT);

        // 检查是否为实体类型
        org.bukkit.entity.EntityType entityType = null;
        try {
            entityType = org.bukkit.entity.EntityType.valueOf(origin);
        } catch (IllegalArgumentException ignored) {
        }

        if (entityType != null) {
            List<String> entities = plugin.getRulesConfig().getStringList("entities");
            if (entities == null || !entities.contains(origin)) {
                sender.sendMessage(lang.getMessage("rule.remove_failed", "origin", origin));
                return;
            }

            entities.remove(origin);
            plugin.getRulesConfig().set("entities", entities);
            plugin.saveRulesConfig();
            plugin.reloadConfiguration();

            sender.sendMessage(lang.getMessage("rule.remove_success", "origin", origin));
            sender.sendMessage(lang.getMessage("setregion.saved"));
            return;
        }

        // 获取当前规则列表 (从 rules.yml)
        List<Map<?, ?>> blocks = plugin.getRulesConfig().getMapList("blocks");
        if (blocks == null) {
            sender.sendMessage(lang.getMessage("rule.remove_failed", "origin", origin));
            return;
        }

        boolean found = false;
        List<Map<String, Object>> newBlocks = new ArrayList<>();
        for (Map<?, ?> map : blocks) {
            Map<String, Object> newMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                newMap.put(entry.getKey().toString(), entry.getValue());
            }

            if (newMap.get("origin").toString().equals(origin)) {
                found = true;
                continue; // Skip adding this to new list
            }
            newBlocks.add(newMap);
        }

        if (!found) {
            sender.sendMessage(lang.getMessage("rule.remove_failed", "origin", origin));
            return;
        }

        plugin.getRulesConfig().set("blocks", newBlocks);
        plugin.saveRulesConfig();
        plugin.reloadConfiguration();

        sender.sendMessage(lang.getMessage("rule.remove_success", "origin", origin));
        sender.sendMessage(lang.getMessage("setregion.saved"));
    }

    /**
     * 处理 rules 子命令
     */
    private void handleRules(CommandSender sender) {
        // 优先读取 rules.yml
        List<Map<?, ?>> blocks = plugin.getRulesConfig().getMapList("blocks");
        if (blocks == null || blocks.isEmpty()) {
            blocks = plugin.getConfig().getMapList("blocks");
        }

        List<String> entities = plugin.getRulesConfig().getStringList("entities");
        if (entities == null || entities.isEmpty()) {
            entities = plugin.getConfig().getStringList("entities.types");
        }

        if ((blocks == null || blocks.isEmpty()) && (entities == null || entities.isEmpty())) {
            sender.sendMessage(lang.getMessage("rule.list_empty"));
            return;
        }

        int total = (blocks != null ? blocks.size() : 0) + (entities != null ? entities.size() : 0);
        sender.sendMessage(lang.getMessage("rule.list_header", "count", String.valueOf(total)));

        if (blocks != null) {
            for (Map<?, ?> map : blocks) {
                String origin = map.get("origin").toString();
                String target = map.get("target").toString();
                sender.sendMessage(lang.getMessage("rule.list_format", "origin", origin, "target", target));
            }
        }

        if (entities != null) {
            for (String entity : entities) {
                sender.sendMessage(lang.getMessage("rule.list_format", "origin", entity, "target", "AIR (Entity)"));
            }
        }
    }

    /**
     * 发送命令用法
     */
    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(lang.getMessage("usage", "label", label));
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(lang.getMessage("help.header"));
        sender.sendMessage(lang.getMessage("help.title"));
        sender.sendMessage("");
        sender.sendMessage(lang.getMessage("help.start", "label", label));
        sender.sendMessage(lang.getMessage("help.start_desc"));
        sender.sendMessage(lang.getMessage("help.start_fresh", "label", label));
        sender.sendMessage(lang.getMessage("help.start_fresh_desc"));
        sender.sendMessage(lang.getMessage("help.stop", "label", label));
        sender.sendMessage(lang.getMessage("help.stop_desc"));
        sender.sendMessage(lang.getMessage("help.status", "label", label));
        sender.sendMessage(lang.getMessage("help.reload", "label", label));
        sender.sendMessage(lang.getMessage("help.setregion", "label", label));
        sender.sendMessage(lang.getMessage("help.rules", "label", label));
        sender.sendMessage(lang.getMessage("help.addrule", "label", label));
        sender.sendMessage(lang.getMessage("help.removerule", "label", label));
        sender.sendMessage(lang.getMessage("help.help", "label", label));
        sender.sendMessage("");
        sender.sendMessage(lang.getMessage("help.aliases"));
        sender.sendMessage(lang.getMessage("help.footer"));
    }
}
