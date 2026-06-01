package org.cubexmc.mountlicense.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleRecord;
import org.cubexmc.mountlicense.service.ParkingService.ActionResult;
import org.cubexmc.mountlicense.service.ReindexResult;

public class MountLicenseCommand implements CommandExecutor, TabCompleter {

    private static final String USE_PERMISSION = "mountlicense.use";
    private static final List<String> ROOT_SUBS = Arrays.asList(
            "help", "list", "info", "park", "unpark", "lock", "unlock", "release",
            "recall", "locate", "key", "trust", "untrust", "admin");
    private static final List<String> ADMIN_SUBS = Arrays.asList("inspect", "give", "reindex", "reload");
    private static final List<String> KEY_SUBS = List.of("unbind");
    private static final List<String> ITEM_KINDS = List.of("license", "key");

    private final MountLicensePlugin plugin;

    public MountLicenseCommand(MountLicensePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            if (!requirePermission(sender, USE_PERMISSION)) return true;
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (requiresUsePermission(sub) && !requirePermission(sender, USE_PERMISSION)) {
            return true;
        }
        switch (sub) {
            case "list":
                return handleList(sender);
            case "info":
                return handleInfo(sender, args);
            case "park":
                return handleParkAction(sender, "park");
            case "unpark":
                return handleParkAction(sender, "unpark");
            case "lock":
                return handleParkAction(sender, "lock");
            case "unlock":
                return handleParkAction(sender, "unlock");
            case "release":
                return handleParkAction(sender, "release");
            case "recall":
                return handleRecall(sender, args);
            case "locate":
                return handleLocate(sender, args);
            case "key":
                return handleKey(sender, args);
            case "trust":
                return handleTrust(sender, args, true);
            case "untrust":
                return handleTrust(sender, args, false);
            case "admin":
                return handleAdmin(sender, args);
            default:
                Map<String, String> p = new HashMap<>();
                p.put("input", args[0]);
                lang().send(sender, "commands.unknown_subcommand", p);
                return true;
        }
    }

    static boolean requiresUsePermission(String subcommand) {
        if (subcommand == null) return false;
        return switch (subcommand.toLowerCase()) {
            case "help", "list", "info", "locate" -> true;
            default -> false;
        };
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        lang().send(sender, "commands.no_permission");
        return false;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang().msg("commands.header"));
        sender.sendMessage(lang().msg("commands.help.line_list"));
        sender.sendMessage(lang().msg("commands.help.line_info"));
        if (sender.hasPermission("mountlicense.park")) {
            sender.sendMessage(lang().msg("commands.help.line_park"));
            sender.sendMessage(lang().msg("commands.help.line_unpark"));
            sender.sendMessage(lang().msg("commands.help.line_lock"));
            sender.sendMessage(lang().msg("commands.help.line_unlock"));
            sender.sendMessage(lang().msg("commands.help.line_release"));
        }
        if (sender.hasPermission("mountlicense.key.use")) {
            sender.sendMessage(lang().msg("commands.help.line_recall"));
            sender.sendMessage(lang().msg("commands.help.line_locate"));
            sender.sendMessage(lang().msg("commands.help.line_key_unbind"));
        }
        if (sender.hasPermission("mountlicense.trust")) {
            sender.sendMessage(lang().msg("commands.help.line_trust"));
            sender.sendMessage(lang().msg("commands.help.line_untrust"));
        }
        if (sender.hasPermission("mountlicense.admin.inspect")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_inspect"));
        }
        if (sender.hasPermission("mountlicense.admin.give")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_give"));
        }
        if (sender.hasPermission("mountlicense.admin.reindex")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_reindex"));
        }
        if (sender.hasPermission("mountlicense.admin.reload")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_reload"));
        }
    }

    private boolean handleParkAction(CommandSender sender, String action) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        if (!player.hasPermission("mountlicense.park")) {
            lang().send(player, "commands.no_permission");
            return true;
        }
        double reach = plugin.configManager().getMaxInteractDistance() + 4.0;
        Entity target = plugin.parkingService().findTargeted(player, reach);
        if (target == null) {
            lang().send(player, "park.no_target");
            return true;
        }

        ActionResult result;
        switch (action) {
            case "park":    result = plugin.parkingService().park(player, target); break;
            case "unpark":  result = plugin.parkingService().unpark(player, target); break;
            case "lock":    result = plugin.parkingService().lock(player, target); break;
            case "unlock":  result = plugin.parkingService().unlock(player, target); break;
            case "release": result = plugin.parkingService().release(player, target); break;
            default: return true;
        }

        Map<String, String> p = new HashMap<>();
        p.put("entity_type", target.getType().name());
        switch (result) {
            case SUCCESS:
                lang().send(player, "park." + action + "_success", p);
                break;
            case NOT_REGISTERED:
                lang().send(player, "park.not_registered", p);
                break;
            case NOT_OWNER:
                lang().send(player, "park.not_owner", p);
                break;
            case ALREADY_IN_STATE:
                lang().send(player, "park.already_in_target_state", p);
                break;
            case CONFIRM_REQUIRED:
                lang().send(player, "park.release_confirm", p);
                break;
            case CONFIRMED:
                lang().send(player, "park.release_done", p);
                break;
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        List<VehicleRecord> records = plugin.vehicleIndex().byOwner(player.getUniqueId());
        if (records.isEmpty()) {
            lang().send(player, "list.empty");
            return true;
        }
        Map<String, String> header = new HashMap<>();
        header.put("count", String.valueOf(records.size()));
        lang().send(player, "list.header", header);

        for (VehicleRecord rec : records) {
            Map<String, String> p = new HashMap<>();
            p.put("profile", rec.profile());
            p.put("short_id", rec.shortId());
            p.put("state", rec.state().name());
            p.put("world", rec.world() == null ? "?" : rec.world());
            player.sendMessage(lang().msg("list.line", p));
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        VehicleRecord rec = resolveVehicle(args[1]);
        if (rec == null) {
            lang().send(player, "info.not_found");
            return true;
        }
        if (!rec.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("mountlicense.admin.inspect")) {
            lang().send(player, "info.not_owner");
            return true;
        }
        sendVehicleInfo(player, rec);
        return true;
    }

    private void sendVehicleInfo(CommandSender to, VehicleRecord rec) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(rec.ownerUuid());
        String ownerName = owner.getName() != null ? owner.getName() : lang().msg("general.unknown_player");
        String lastSeen = rec.lastSeenAt() <= 0
                ? lang().msg("general.never")
                : java.time.Instant.ofEpochMilli(rec.lastSeenAt()).toString();

        to.sendMessage(lang().msg("info.header"));
        Map<String, String> p = new HashMap<>();
        p.put("vehicle_id", rec.shortId());
        to.sendMessage(lang().msg("info.line_id", p));
        p.clear(); p.put("profile", rec.profile());
        to.sendMessage(lang().msg("info.line_profile", p));
        p.clear(); p.put("entity_type", rec.entityType());
        to.sendMessage(lang().msg("info.line_entity", p));
        p.clear(); p.put("owner", ownerName);
        to.sendMessage(lang().msg("info.line_owner", p));
        p.clear(); p.put("state", rec.state().name());
        to.sendMessage(lang().msg("info.line_state", p));
        if (rec.world() != null) {
            p.clear();
            p.put("world", rec.world());
            p.put("x", String.format("%.1f", rec.x()));
            p.put("y", String.format("%.1f", rec.y()));
            p.put("z", String.format("%.1f", rec.z()));
            to.sendMessage(lang().msg("info.line_location", p));
        }
        p.clear(); p.put("last_seen", lastSeen);
        to.sendMessage(lang().msg("info.line_last_seen", p));

        p.clear(); p.put("trustees", formatTrustees(rec));
        to.sendMessage(lang().msg("info.line_trustees", p));
    }

    private String formatTrustees(VehicleRecord rec) {
        if (rec.trustees().isEmpty()) {
            return lang().msg("info.trustees_none");
        }
        StringBuilder sb = new StringBuilder();
        synchronized (rec.trustees()) {
            for (UUID id : rec.trustees()) {
                if (sb.length() > 0) sb.append(", ");
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                String name = op.getName();
                sb.append(name == null ? id.toString().substring(0, 8) : name);
            }
        }
        return sb.toString();
    }

    private boolean handleRecall(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        if (!player.hasPermission("mountlicense.key.use")) {
            lang().send(player, "commands.no_permission");
            return true;
        }
        UUID vehicleId = null;
        if (args.length >= 2) {
            VehicleRecord record = resolveVehicle(args[1]);
            if (record == null) {
                lang().send(player, "recall.fail_not_found");
                return true;
            }
            vehicleId = record.vehicleId();
        }
        var result = vehicleId == null
                ? plugin.recallService().recallNearest(player)
                : plugin.recallService().recallById(player, vehicleId);
        plugin.recallService().sendResultMessage(player, result, vehicleId);
        return true;
    }

    private boolean handleLocate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        VehicleRecord record = resolveVehicle(args[1]);
        if (record == null) {
            lang().send(player, "info.not_found");
            return true;
        }
        if (!record.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("mountlicense.admin.bypass")) {
            lang().send(player, "info.not_owner");
            return true;
        }
        var info = plugin.recallService().locate(player, record.vehicleId());
        Map<String, String> p = new HashMap<>();
        p.put("short_id", record.shortId());
        p.put("profile", record.profile());
        if (record.world() != null) {
            p.put("world", record.world());
            p.put("x", String.format("%.0f", record.x()));
            p.put("y", String.format("%.0f", record.y()));
            p.put("z", String.format("%.0f", record.z()));
        } else {
            p.put("world", "?");
            p.put("x", "?");
            p.put("y", "?");
            p.put("z", "?");
        }
        lang().send(player, info.loaded() ? "locate.loaded" : "locate.unloaded", p);
        return true;
    }

    private boolean handleKey(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        if (args.length < 2 || !"unbind".equalsIgnoreCase(args[1])) {
            sendHelp(sender);
            return true;
        }
        if (!player.hasPermission("mountlicense.key.use")) {
            lang().send(player, "commands.no_permission");
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!plugin.itemFactory().isKey(hand)) {
            lang().send(player, "key.unbind_no_key");
            return true;
        }
        boolean changed = plugin.itemFactory().unbindKey(hand);
        if (changed) {
            player.getInventory().setItemInMainHand(hand);
            lang().send(player, "key.unbind_success");
        } else {
            lang().send(player, "key.unbind_already_unbound");
        }
        return true;
    }

    private boolean handleTrust(CommandSender sender, String[] args, boolean add) {
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        if (!player.hasPermission("mountlicense.trust")) {
            lang().send(player, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Map<String, String> p = new HashMap<>();
            p.put("player", args[1]);
            lang().send(player, "commands.player_not_found", p);
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            lang().send(player, "trust.cannot_trust_self");
            return true;
        }

        double reach = plugin.configManager().getMaxInteractDistance() + 4.0;
        Entity targetEntity = plugin.parkingService().findTargeted(player, reach);
        if (targetEntity == null) {
            lang().send(player, "trust.no_target");
            return true;
        }
        UUID vehicleId = plugin.ownershipService().readVehicleId(targetEntity);
        if (vehicleId == null) {
            lang().send(player, "trust.target_not_registered");
            return true;
        }
        if (!plugin.ownershipService().isOwner(targetEntity, player.getUniqueId())
                && !player.hasPermission("mountlicense.admin.bypass")) {
            lang().send(player, "trust.not_owner");
            return true;
        }
        VehicleRecord record = plugin.vehicleIndex().byId(vehicleId);
        if (record == null) {
            lang().send(player, "trust.target_not_registered");
            return true;
        }

        Map<String, String> p = new HashMap<>();
        p.put("player", target.getName());
        p.put("entity_type", targetEntity.getType().name());
        p.put("short_id", record.shortId());

        if (add) {
            int max = plugin.configManager().getMaxTrusteesPerVehicle();
            synchronized (record.trustees()) {
                if (record.isTrustee(target.getUniqueId())) {
                    lang().send(player, "trust.already_trusted", p);
                    return true;
                }
                if (max >= 0 && record.trustees().size() >= max) {
                    p.put("max", String.valueOf(max));
                    lang().send(player, "trust.fail_max", p);
                    return true;
                }
                record.addTrustee(target.getUniqueId());
            }
            plugin.vehicleIndex().markDirty();
            lang().send(player, "trust.added", p);
            if (target.isOnline()) {
                lang().send(target, "trust.notify_added", p);
            }
        } else {
            synchronized (record.trustees()) {
                boolean removed = record.removeTrustee(target.getUniqueId());
                if (!removed) {
                    lang().send(player, "trust.not_a_trustee", p);
                    return true;
                }
            }
            plugin.vehicleIndex().markDirty();
            lang().send(player, "trust.removed", p);
            if (target.isOnline()) {
                lang().send(target, "trust.notify_removed", p);
            }
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        String adminSub = args[1].toLowerCase();
        switch (adminSub) {
            case "inspect":
                return handleAdminInspect(sender);
            case "give":
                return handleAdminGive(sender, args);
            case "reindex":
                return handleAdminReindex(sender);
            case "reload":
                return handleAdminReload(sender);
            default:
                Map<String, String> p = new HashMap<>();
                p.put("input", args[1]);
                lang().send(sender, "commands.unknown_subcommand", p);
                return true;
        }
    }

    private boolean handleAdminInspect(CommandSender sender) {
        if (!sender.hasPermission("mountlicense.admin.inspect")) {
            lang().send(sender, "commands.no_permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            lang().send(sender, "commands.player_only");
            return true;
        }
        double reach = plugin.configManager().getMaxInteractDistance() + 4.0;
        org.bukkit.util.RayTraceResult ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                reach,
                e -> e != player);
        Entity target = ray == null ? null : ray.getHitEntity();
        if (target == null) {
            lang().send(player, "admin.inspect.no_target");
            return true;
        }
        UUID vehicleId = plugin.registryService().readVehicleId(target);
        if (vehicleId == null) {
            Map<String, String> p = new HashMap<>();
            p.put("entity_type", target.getType().name());
            lang().send(player, "admin.inspect.target_unregistered", p);
            return true;
        }
        VehicleRecord rec = plugin.vehicleIndex().byId(vehicleId);
        Map<String, String> p = new HashMap<>();
        p.put("vehicle_id", rec == null ? vehicleId.toString() : rec.shortId());
        lang().send(player, "admin.inspect.target_registered", p);
        if (rec != null) sendVehicleInfo(player, rec);
        return true;
    }

    private boolean handleAdminGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mountlicense.admin.give")) {
            lang().send(sender, "commands.no_permission");
            return true;
        }
        if (args.length < 4) {
            sendHelp(sender);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            Map<String, String> p = new HashMap<>();
            p.put("player", args[2]);
            lang().send(sender, "commands.player_not_found", p);
            return true;
        }
        String kind = args[3].toLowerCase();
        int amount = args.length >= 5 ? safeParseInt(args[4], 1) : 1;
        ItemStack item;
        switch (kind) {
            case "license":
                item = plugin.itemFactory().createLicense(amount);
                break;
            case "key":
                item = plugin.itemFactory().createKey(amount);
                break;
            default:
                Map<String, String> p = new HashMap<>();
                p.put("input", kind);
                lang().send(sender, "commands.unknown_subcommand", p);
                return true;
        }
        java.util.HashMap<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
        Map<String, String> p = new HashMap<>();
        p.put("player", target.getName());
        p.put("amount", String.valueOf(amount));
        lang().send(sender, "admin.give.success", p);
        lang().send(target, "admin.give.received", p);
        return true;
    }

    private boolean handleAdminReindex(CommandSender sender) {
        if (!sender.hasPermission("mountlicense.admin.reindex")) {
            lang().send(sender, "commands.no_permission");
            return true;
        }
        lang().send(sender, "admin.reindex.started");
        ReindexResult result = plugin.registryService().reindexLoadedEntities();
        Map<String, String> p = new HashMap<>();
        p.put("scanned", String.valueOf(result.scanned()));
        p.put("recovered", String.valueOf(result.recovered()));
        lang().send(sender, "admin.reindex.finished", p);
        return true;
    }

    private boolean handleAdminReload(CommandSender sender) {
        if (!sender.hasPermission("mountlicense.admin.reload")) {
            lang().send(sender, "commands.no_permission");
            return true;
        }
        plugin.reloadAll();
        lang().send(sender, "admin.reload.success");
        return true;
    }

    private VehicleRecord resolveVehicle(String input) {
        if (input == null || input.isEmpty()) return null;
        try {
            return plugin.vehicleIndex().byId(UUID.fromString(input));
        } catch (IllegalArgumentException ex) {
            String prefix = input.toLowerCase();
            for (VehicleRecord rec : plugin.vehicleIndex().all()) {
                if (rec.shortId().toLowerCase().startsWith(prefix)
                        || rec.vehicleId().toString().toLowerCase().startsWith(prefix)) {
                    return rec;
                }
            }
            return null;
        }
    }

    private int safeParseInt(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private LanguageManager lang() {
        return plugin.languageManager();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT_SUBS, args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])) {
            return filter(ADMIN_SUBS, args[1]);
        }
        if (args.length == 2 && ("info".equalsIgnoreCase(args[0])
                || "recall".equalsIgnoreCase(args[0])
                || "locate".equalsIgnoreCase(args[0]))) {
            if (sender instanceof Player player) {
                List<String> ids = plugin.vehicleIndex().byOwner(player.getUniqueId()).stream()
                        .map(VehicleRecord::shortId)
                        .collect(Collectors.toList());
                return filter(ids, args[1]);
            }
        }
        if (args.length == 2 && "key".equalsIgnoreCase(args[0])) {
            return filter(KEY_SUBS, args[1]);
        }
        if (args.length == 2 && ("trust".equalsIgnoreCase(args[0])
                || "untrust".equalsIgnoreCase(args[0]))) {
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).collect(Collectors.toList());
            return filter(names, args[1]);
        }
        if (args.length == 3 && "admin".equalsIgnoreCase(args[0]) && "give".equalsIgnoreCase(args[1])) {
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).collect(Collectors.toList());
            return filter(names, args[2]);
        }
        if (args.length == 4 && "admin".equalsIgnoreCase(args[0]) && "give".equalsIgnoreCase(args[1])) {
            return filter(ITEM_KINDS, args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> source, String prefix) {
        if (source == null || source.isEmpty()) return List.of();
        String p = prefix == null ? "" : prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase().startsWith(p)) out.add(s);
        }
        return out;
    }
}
