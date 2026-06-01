package org.cubexmc.booklite.command;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.lang.LanguageManager;
import org.cubexmc.booklite.model.BookRecord;
import org.cubexmc.booklite.service.BookCodec;
import org.cubexmc.booklite.service.BookRestorer;
import org.cubexmc.booklite.service.BookService;
import org.cubexmc.booklite.storage.BookRepository;

public class BookLiteCommand implements CommandExecutor, TabCompleter {

    private static final int LIST_PAGE_SIZE = 8;
    private static final int TAB_ID_LIMIT = 20;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final List<String> ROOT_SUBS = Arrays.asList(
            "help", "status", "reload", "convert", "restore", "info", "read",
            "get", "delete", "purge", "list", "restorecontainer");

    private final BookLitePlugin plugin;
    private final BookService books;
    private final BookCodec codec;
    private final BookRestorer restorer;
    private final LanguageManager lang;

    public BookLiteCommand(BookLitePlugin plugin, BookService books,
                           BookCodec codec, BookRestorer restorer, LanguageManager lang) {
        this.plugin = plugin;
        this.books = books;
        this.codec = codec;
        this.restorer = restorer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(sender);
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            case "convert" -> handleConvert(sender);
            case "restore" -> handleRestore(sender);
            case "info" -> handleInfo(sender, args);
            case "read" -> handleRead(sender, args);
            case "get" -> handleGet(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "purge" -> handlePurge(sender, args);
            case "list" -> handleList(sender, args);
            case "restorecontainer" -> handleRestoreContainer(sender);
            default -> {
                Map<String, String> p = new HashMap<>();
                p.put("input", args[0]);
                lang.send(sender, "commands.unknown", p);
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang.msg("commands.header"));
        if (sender.hasPermission("booklite.convert")) sender.sendMessage(lang.msg("commands.help_convert"));
        if (sender.hasPermission("booklite.restore")) sender.sendMessage(lang.msg("commands.help_restore"));
        if (sender.hasPermission("booklite.admin.status")) sender.sendMessage(lang.msg("commands.help_status"));
        if (sender.hasPermission("booklite.admin.list")) sender.sendMessage(lang.msg("commands.help_list"));
        if (sender.hasPermission("booklite.admin.info")) sender.sendMessage(lang.msg("commands.help_info"));
        if (sender.hasPermission("booklite.admin.read")) sender.sendMessage(lang.msg("commands.help_read"));
        if (sender.hasPermission("booklite.admin.get")) sender.sendMessage(lang.msg("commands.help_get"));
        if (sender.hasPermission("booklite.admin.delete")) sender.sendMessage(lang.msg("commands.help_delete"));
        if (sender.hasPermission("booklite.admin.purge")) sender.sendMessage(lang.msg("commands.help_purge"));
        if (sender.hasPermission("booklite.admin.restorecontainer")) {
            sender.sendMessage(lang.msg("commands.help_restorecontainer"));
        }
        if (sender.hasPermission("booklite.admin.reload")) sender.sendMessage(lang.msg("commands.help_reload"));
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("booklite.admin.status")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        try {
            BookRepository.Stats stats = books.stats();
            Map<String, String> p = new HashMap<>();
            p.put("total", String.valueOf(stats.total()));
            p.put("cache", String.valueOf(books.cacheSize()));
            p.put("uninstall", String.valueOf(plugin.configManager().isUninstallMode()));
            p.put("last_accessed", formatTime(stats.lastAccessedAt()));
            lang.send(sender, "status.line", p);
        } catch (SQLException ex) {
            books.logStorageFailure("status", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("booklite.admin.reload")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        plugin.reloadAll();
        lang.send(sender, "commands.reload_done");
        return true;
    }

    private boolean handleConvert(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "commands.player_only");
            return true;
        }
        if (!player.hasPermission("booklite.convert")) {
            lang.send(player, "commands.no_permission");
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!codec.isWrittenBook(item) || codec.isBookLite(item)) {
            lang.send(player, "book.fail_hold_vanilla");
            return true;
        }

        BookMeta meta = (BookMeta) item.getItemMeta();
        BookRecord draft = codec.createRecord(meta, player.getName());
        BookCodec.ValidationResult validation = codec.validate(draft);
        if (!validation.ok()) {
            sendValidation(player, validation);
            return true;
        }

        try {
            BookRecord record = books.saveOrGet(draft);
            int generation = codec.generationToInt(meta);
            player.getInventory().setItemInMainHand(codec.createShell(record, generation, item.getAmount()));
            Map<String, String> p = placeholders(record);
            lang.send(player, "book.converted", p);
            if (plugin.configManager().isLogConversions()) {
                plugin.getLogger().info(player.getName() + " converted book " + record.id());
            }
        } catch (SQLException ex) {
            books.logStorageFailure("convert", ex);
            lang.send(player, "book.fail_storage");
        }
        return true;
    }

    private boolean handleRestore(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "commands.player_only");
            return true;
        }
        if (!player.hasPermission("booklite.restore")) {
            lang.send(player, "commands.no_permission");
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!codec.isBookLite(item)) {
            lang.send(player, "book.fail_hold_booklite");
            return true;
        }
        String id = codec.readBookId(item);
        try {
            BookService.Resolution resolution = books.resolve(id);
            if (resolution.ambiguous()) {
                lang.send(player, "commands.ambiguous_id");
                return true;
            }
            BookRecord record = resolution.record();
            if (record == null) {
                lang.send(player, "book.fail_missing");
                return true;
            }
            int generation = codec.readGeneration(item);
            player.getInventory().setItemInMainHand(codec.createFullBook(record, generation, item.getAmount()));
            lang.send(player, "book.restored", placeholders(record));
            if (plugin.configManager().isLogRestores()) {
                plugin.getLogger().info(player.getName() + " restored book " + record.id());
            }
        } catch (SQLException ex) {
            books.logStorageFailure("restore", ex);
            lang.send(player, "book.fail_storage");
        }
        return true;
    }

    private boolean handleRead(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "commands.player_only");
            return true;
        }
        if (!sender.hasPermission("booklite.admin.read")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        try {
            // Admin reads must not touch last_accessed_at; the stale-purge logic
            // relies on it reflecting real player usage only.
            BookService.Resolution resolution = books.resolve(args[1]);
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id");
                return true;
            }
            BookRecord record = resolution.record();
            player.openBook(codec.createReadable(record, 0));
            if (record == null) {
                lang.send(sender, "book.fail_missing");
            } else {
                lang.send(sender, "admin.read_opened", placeholders(record));
                if (plugin.configManager().isLogAdminReads()) {
                    plugin.getLogger().info(sender.getName() + " admin-read book " + record.id());
                }
            }
        } catch (SQLException ex) {
            books.logStorageFailure("read", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("booklite.admin.info")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        try {
            BookService.Resolution resolution = books.resolve(args[1]);
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id");
                return true;
            }
            BookRecord record = resolution.record();
            if (record == null) {
                lang.send(sender, "book.fail_missing");
                return true;
            }
            Map<String, String> p = placeholders(record);
            p.put("pages", String.valueOf(record.totalPages()));
            p.put("hash", record.contentHash());
            p.put("created", Instant.ofEpochMilli(record.createdAt()).toString());
            p.put("updated", Instant.ofEpochMilli(record.updatedAt()).toString());
            p.put("last_accessed", formatTime(record.lastAccessedAt()));
            sender.sendMessage(lang.msg("admin.info_header", p));
            sender.sendMessage(lang.msg("admin.info_title", p));
            sender.sendMessage(lang.msg("admin.info_author", p));
            sender.sendMessage(lang.msg("admin.info_pages", p));
            sender.sendMessage(lang.msg("admin.info_hash", p));
            sender.sendMessage(lang.msg("admin.info_times", p));
        } catch (SQLException ex) {
            books.logStorageFailure("info", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handleGet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "commands.player_only");
            return true;
        }
        if (!sender.hasPermission("booklite.admin.get")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        try {
            BookService.Resolution resolution = books.resolve(args[1]);
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id");
                return true;
            }
            BookRecord record = resolution.record();
            if (record == null) {
                lang.send(sender, "book.fail_missing");
                return true;
            }
            Map<Integer, ItemStack> leftover = player.getInventory()
                    .addItem(codec.createShell(record, 0, 1));
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            lang.send(sender, leftover.isEmpty() ? "admin.get_done" : "admin.get_dropped", placeholders(record));
        } catch (SQLException ex) {
            books.logStorageFailure("get", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("booklite.admin.delete")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        try {
            BookService.Resolution resolution = books.resolve(args[1]);
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id");
                return true;
            }
            BookRecord record = resolution.record();
            if (record == null) {
                lang.send(sender, "book.fail_missing");
                return true;
            }
            boolean changed = books.delete(record.id());
            lang.send(sender, changed ? "admin.deleted" : "admin.delete_no_change");
        } catch (SQLException ex) {
            books.logStorageFailure("delete", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handlePurge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("booklite.admin.purge")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        int days = args.length >= 2 ? parseNonNegative(args[1], -1) : -1;
        if (days < 0 || args.length < 3 || !"confirm".equalsIgnoreCase(args[2])) {
            lang.send(sender, "admin.purge_usage");
            return true;
        }

        long cutoff = System.currentTimeMillis() - (days * DAY_MILLIS);
        try {
            int purged = books.purgeStale(cutoff);
            Map<String, String> p = new HashMap<>();
            p.put("count", String.valueOf(purged));
            p.put("days", String.valueOf(days));
            lang.send(sender, "admin.purged", p);
        } catch (SQLException ex) {
            books.logStorageFailure("purge", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("booklite.admin.list")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        int page = args.length >= 2 ? parsePositive(args[1], 1) : 1;
        try {
            List<BookRecord> records = books.list(page, LIST_PAGE_SIZE);
            if (records.isEmpty()) {
                lang.send(sender, "admin.list_empty");
                return true;
            }
            Map<String, String> header = new HashMap<>();
            header.put("page", String.valueOf(page));
            lang.send(sender, "admin.list_header", header);
            for (BookRecord record : records) {
                Map<String, String> p = placeholders(record);
                p.put("pages", String.valueOf(record.totalPages()));
                p.put("created", Instant.ofEpochMilli(record.createdAt()).toString());
                p.put("last_accessed", formatTime(record.lastAccessedAt()));
                sender.sendMessage(lang.msg("admin.list_line", p));
            }
        } catch (SQLException ex) {
            books.logStorageFailure("list", ex);
            lang.send(sender, "book.fail_storage");
        }
        return true;
    }

    private boolean handleRestoreContainer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "commands.player_only");
            return true;
        }
        if (!sender.hasPermission("booklite.admin.restorecontainer")) {
            lang.send(sender, "commands.no_permission");
            return true;
        }
        Inventory inv = resolveRestoreInventory(player);
        if (inv == null) {
            lang.send(sender, "admin.restorecontainer_no_target");
            return true;
        }
        int restored = restorer.restoreInventoryNow(inv, plugin.configManager().getMaxItemsPerTick());
        Map<String, String> p = new HashMap<>();
        p.put("count", String.valueOf(restored));
        lang.send(sender, "admin.restorecontainer_done", p);
        return true;
    }

    private Inventory resolveRestoreInventory(Player player) {
        Block target = player.getTargetBlockExact(6);
        if (target != null && target.getState() instanceof Container container) {
            return container.getInventory();
        }

        Inventory open = player.getOpenInventory().getTopInventory();
        if (open != null && open.getType() != InventoryType.CRAFTING
                && open.getType() != InventoryType.CREATIVE) {
            return open;
        }
        return null;
    }

    private void sendValidation(CommandSender sender, BookCodec.ValidationResult validation) {
        Map<String, String> p = new HashMap<>();
        p.put("actual", String.valueOf(validation.actual()));
        p.put("max", String.valueOf(validation.max()));
        lang.send(sender, validation.key(), p);
    }

    private Map<String, String> placeholders(BookRecord record) {
        Map<String, String> p = new HashMap<>();
        p.put("id", record.id());
        p.put("short_id", record.shortId());
        p.put("title", record.title());
        p.put("author", record.author());
        return p;
    }

    private int parsePositive(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int parseNonNegative(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw);
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT_SUBS, args[0]);
        }
        if (args.length == 2 && "purge".equalsIgnoreCase(args[0])) {
            return filter(List.of("30", "90", "0"), args[1]);
        }
        if (args.length == 3 && "purge".equalsIgnoreCase(args[0])) {
            return filter(List.of("confirm"), args[2]);
        }
        if (args.length == 2) {
            return completeBookIds(sender, args[0], args[1]);
        }
        return List.of();
    }

    private List<String> completeBookIds(CommandSender sender, String subcommand, String prefix) {
        String permission = switch (subcommand.toLowerCase()) {
            case "info" -> "booklite.admin.info";
            case "read" -> "booklite.admin.read";
            case "get" -> "booklite.admin.get";
            case "delete" -> "booklite.admin.delete";
            default -> null;
        };
        if (permission == null || !sender.hasPermission(permission)) return List.of();
        try {
            return books.completeIds(prefix, TAB_ID_LIMIT);
        } catch (SQLException ex) {
            books.logStorageFailure("tab complete", ex);
            return List.of();
        }
    }

    private List<String> filter(List<String> source, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase().startsWith(p)) out.add(value);
        }
        return out;
    }

    private String formatTime(Long millis) {
        return millis == null ? "-" : Instant.ofEpochMilli(millis).toString();
    }
}
