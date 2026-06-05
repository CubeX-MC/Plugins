package org.cubexmc.booklite.command

import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import org.bukkit.block.Container
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.booklite.lang.LanguageManager
import org.cubexmc.booklite.model.BookRecord
import org.cubexmc.booklite.service.BookCodec
import org.cubexmc.booklite.service.BookRestorer
import org.cubexmc.booklite.service.BookService

class BookLiteCommand(
    private val plugin: BookLitePlugin,
    private val books: BookService,
    private val codec: BookCodec,
    private val restorer: BookRestorer,
    private val lang: LanguageManager,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || "help".equals(args[0], ignoreCase = true)) {
            sendHelp(sender)
            return true
        }
        return when (args[0].lowercase(Locale.getDefault())) {
            "status" -> handleStatus(sender)
            "reload" -> handleReload(sender)
            "convert" -> handleConvert(sender)
            "restore" -> handleRestore(sender)
            "info" -> handleInfo(sender, args)
            "read" -> handleRead(sender, args)
            "get" -> handleGet(sender, args)
            "delete" -> handleDelete(sender, args)
            "purge" -> handlePurge(sender, args)
            "list" -> handleList(sender, args)
            "restorecontainer" -> handleRestoreContainer(sender)
            else -> {
                val placeholders = HashMap<String, String>()
                placeholders["input"] = args[0]
                lang.send(sender, "commands.unknown", placeholders)
                true
            }
        }
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(lang.msg("commands.header"))
        if (sender.hasPermission("booklite.convert")) sender.sendMessage(lang.msg("commands.help_convert"))
        if (sender.hasPermission("booklite.restore")) sender.sendMessage(lang.msg("commands.help_restore"))
        if (sender.hasPermission("booklite.admin.status")) sender.sendMessage(lang.msg("commands.help_status"))
        if (sender.hasPermission("booklite.admin.list")) sender.sendMessage(lang.msg("commands.help_list"))
        if (sender.hasPermission("booklite.admin.info")) sender.sendMessage(lang.msg("commands.help_info"))
        if (sender.hasPermission("booklite.admin.read")) sender.sendMessage(lang.msg("commands.help_read"))
        if (sender.hasPermission("booklite.admin.get")) sender.sendMessage(lang.msg("commands.help_get"))
        if (sender.hasPermission("booklite.admin.delete")) sender.sendMessage(lang.msg("commands.help_delete"))
        if (sender.hasPermission("booklite.admin.purge")) sender.sendMessage(lang.msg("commands.help_purge"))
        if (sender.hasPermission("booklite.admin.restorecontainer")) {
            sender.sendMessage(lang.msg("commands.help_restorecontainer"))
        }
        if (sender.hasPermission("booklite.admin.reload")) sender.sendMessage(lang.msg("commands.help_reload"))
    }

    private fun handleStatus(sender: CommandSender): Boolean {
        if (!sender.hasPermission("booklite.admin.status")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        try {
            val stats = books.stats()
            val placeholders = HashMap<String, String>()
            placeholders["total"] = stats.total().toString()
            placeholders["cache"] = books.cacheSize().toString()
            placeholders["uninstall"] = plugin.configManager().isUninstallMode().toString()
            placeholders["last_accessed"] = formatTime(stats.lastAccessedAt())
            lang.send(sender, "status.line", placeholders)
        } catch (exception: SQLException) {
            books.logStorageFailure("status", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("booklite.admin.reload")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        plugin.reloadAll()
        lang.send(sender, "commands.reload_done")
        return true
    }

    private fun handleConvert(sender: CommandSender): Boolean {
        if (sender !is Player) {
            lang.send(sender, "commands.player_only")
            return true
        }
        if (!sender.hasPermission("booklite.convert")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        val item = sender.inventory.itemInMainHand
        if (!codec.isWrittenBook(item) || codec.isBookLite(item)) {
            lang.send(sender, "book.fail_hold_vanilla")
            return true
        }

        val meta = item.itemMeta as BookMeta
        val draft = codec.createRecord(meta, sender.name)
        val validation = codec.validate(draft)
        if (!validation.ok()) {
            sendValidation(sender, validation)
            return true
        }

        try {
            val record = books.saveOrGet(draft)
            val generation = codec.generationToInt(meta)
            sender.inventory.setItemInMainHand(codec.createShell(record, generation, item.amount))
            val placeholders = placeholders(record)
            lang.send(sender, "book.converted", placeholders)
            if (plugin.configManager().isLogConversions()) {
                plugin.logger.info(sender.name + " converted book " + record.id())
            }
        } catch (exception: SQLException) {
            books.logStorageFailure("convert", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleRestore(sender: CommandSender): Boolean {
        if (sender !is Player) {
            lang.send(sender, "commands.player_only")
            return true
        }
        if (!sender.hasPermission("booklite.restore")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        val item = sender.inventory.itemInMainHand
        if (!codec.isBookLite(item)) {
            lang.send(sender, "book.fail_hold_booklite")
            return true
        }
        val id = codec.readBookId(item)
        try {
            val resolution = books.resolve(id)
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id")
                return true
            }
            val record = resolution.record()
            if (record == null) {
                lang.send(sender, "book.fail_missing")
                return true
            }
            val generation = codec.readGeneration(item)
            sender.inventory.setItemInMainHand(codec.createFullBook(record, generation, item.amount))
            lang.send(sender, "book.restored", placeholders(record))
            if (plugin.configManager().isLogRestores()) {
                plugin.logger.info(sender.name + " restored book " + record.id())
            }
        } catch (exception: SQLException) {
            books.logStorageFailure("restore", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleRead(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            lang.send(sender, "commands.player_only")
            return true
        }
        if (!sender.hasPermission("booklite.admin.read")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        try {
            // Admin reads must not touch last_accessed_at; the stale-purge logic
            // relies on it reflecting real player usage only.
            val resolution = books.resolve(args[1])
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id")
                return true
            }
            val record = resolution.record()
            sender.openBook(codec.createReadable(record, 0))
            if (record == null) {
                lang.send(sender, "book.fail_missing")
            } else {
                lang.send(sender, "admin.read_opened", placeholders(record))
                if (plugin.configManager().isLogAdminReads()) {
                    plugin.logger.info(sender.name + " admin-read book " + record.id())
                }
            }
        } catch (exception: SQLException) {
            books.logStorageFailure("read", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("booklite.admin.info")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        try {
            val resolution = books.resolve(args[1])
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id")
                return true
            }
            val record = resolution.record()
            if (record == null) {
                lang.send(sender, "book.fail_missing")
                return true
            }
            val placeholders = placeholders(record)
            placeholders["pages"] = record.totalPages().toString()
            placeholders["hash"] = record.contentHash()
            placeholders["created"] = Instant.ofEpochMilli(record.createdAt()).toString()
            placeholders["updated"] = Instant.ofEpochMilli(record.updatedAt()).toString()
            placeholders["last_accessed"] = formatTime(record.lastAccessedAt())
            sender.sendMessage(lang.msg("admin.info_header", placeholders))
            sender.sendMessage(lang.msg("admin.info_title", placeholders))
            sender.sendMessage(lang.msg("admin.info_author", placeholders))
            sender.sendMessage(lang.msg("admin.info_pages", placeholders))
            sender.sendMessage(lang.msg("admin.info_hash", placeholders))
            sender.sendMessage(lang.msg("admin.info_times", placeholders))
        } catch (exception: SQLException) {
            books.logStorageFailure("info", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleGet(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            lang.send(sender, "commands.player_only")
            return true
        }
        if (!sender.hasPermission("booklite.admin.get")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        try {
            val resolution = books.resolve(args[1])
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id")
                return true
            }
            val record = resolution.record()
            if (record == null) {
                lang.send(sender, "book.fail_missing")
                return true
            }
            val leftover: Map<Int, ItemStack> = sender.inventory.addItem(codec.createShell(record, 0, 1))
            for (item in leftover.values) {
                sender.world.dropItemNaturally(sender.location, item)
            }
            lang.send(sender, if (leftover.isEmpty()) "admin.get_done" else "admin.get_dropped", placeholders(record))
        } catch (exception: SQLException) {
            books.logStorageFailure("get", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("booklite.admin.delete")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        try {
            val resolution = books.resolve(args[1])
            if (resolution.ambiguous()) {
                lang.send(sender, "commands.ambiguous_id")
                return true
            }
            val record = resolution.record()
            if (record == null) {
                lang.send(sender, "book.fail_missing")
                return true
            }
            val changed = books.delete(record.id())
            lang.send(sender, if (changed) "admin.deleted" else "admin.delete_no_change")
        } catch (exception: SQLException) {
            books.logStorageFailure("delete", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handlePurge(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("booklite.admin.purge")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        val days = if (args.size >= 2) parseNonNegative(args[1], -1) else -1
        if (days < 0 || args.size < 3 || !"confirm".equals(args[2], ignoreCase = true)) {
            lang.send(sender, "admin.purge_usage")
            return true
        }

        val cutoff = System.currentTimeMillis() - days * DAY_MILLIS
        try {
            val purged = books.purgeStale(cutoff)
            val placeholders = HashMap<String, String>()
            placeholders["count"] = purged.toString()
            placeholders["days"] = days.toString()
            lang.send(sender, "admin.purged", placeholders)
        } catch (exception: SQLException) {
            books.logStorageFailure("purge", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleList(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("booklite.admin.list")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        val page = if (args.size >= 2) parsePositive(args[1], 1) else 1
        try {
            val records = books.list(page, LIST_PAGE_SIZE)
            if (records.isEmpty()) {
                lang.send(sender, "admin.list_empty")
                return true
            }
            val header = HashMap<String, String>()
            header["page"] = page.toString()
            lang.send(sender, "admin.list_header", header)
            for (record in records) {
                val placeholders = placeholders(record)
                placeholders["pages"] = record.totalPages().toString()
                placeholders["created"] = Instant.ofEpochMilli(record.createdAt()).toString()
                placeholders["last_accessed"] = formatTime(record.lastAccessedAt())
                sender.sendMessage(lang.msg("admin.list_line", placeholders))
            }
        } catch (exception: SQLException) {
            books.logStorageFailure("list", exception)
            lang.send(sender, "book.fail_storage")
        }
        return true
    }

    private fun handleRestoreContainer(sender: CommandSender): Boolean {
        if (sender !is Player) {
            lang.send(sender, "commands.player_only")
            return true
        }
        if (!sender.hasPermission("booklite.admin.restorecontainer")) {
            lang.send(sender, "commands.no_permission")
            return true
        }
        val inventory = resolveRestoreInventory(sender)
        if (inventory == null) {
            lang.send(sender, "admin.restorecontainer_no_target")
            return true
        }
        val restored = restorer.restoreInventoryNow(inventory, plugin.configManager().getMaxItemsPerTick())
        val placeholders = HashMap<String, String>()
        placeholders["count"] = restored.toString()
        lang.send(sender, "admin.restorecontainer_done", placeholders)
        return true
    }

    private fun resolveRestoreInventory(player: Player): Inventory? {
        val target = player.getTargetBlockExact(6)
        val state = target?.state
        if (state is Container) {
            return state.inventory
        }

        val open = player.openInventory.topInventory
        if (open.type != InventoryType.CRAFTING && open.type != InventoryType.CREATIVE) {
            return open
        }
        return null
    }

    private fun sendValidation(sender: CommandSender, validation: BookCodec.ValidationResult) {
        val placeholders = HashMap<String, String>()
        placeholders["actual"] = validation.actual().toString()
        placeholders["max"] = validation.max().toString()
        lang.send(sender, validation.key(), placeholders)
    }

    private fun placeholders(record: BookRecord): HashMap<String, String> {
        val placeholders = HashMap<String, String>()
        placeholders["id"] = record.id()
        placeholders["short_id"] = record.shortId()
        placeholders["title"] = record.title()
        placeholders["author"] = record.author()
        return placeholders
    }

    private fun parsePositive(raw: String, fallback: Int): Int =
        try {
            val value = raw.toInt()
            if (value > 0) value else fallback
        } catch (exception: NumberFormatException) {
            fallback
        }

    private fun parseNonNegative(raw: String, fallback: Int): Int =
        try {
            val value = raw.toInt()
            if (value >= 0) value else fallback
        } catch (exception: NumberFormatException) {
            fallback
        }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return filter(ROOT_SUBS, args[0])
        }
        if (args.size == 2 && "purge".equals(args[0], ignoreCase = true)) {
            return filter(listOf("30", "90", "0"), args[1])
        }
        if (args.size == 3 && "purge".equals(args[0], ignoreCase = true)) {
            return filter(listOf("confirm"), args[2])
        }
        if (args.size == 2) {
            return completeBookIds(sender, args[0], args[1])
        }
        return emptyList()
    }

    private fun completeBookIds(sender: CommandSender, subcommand: String, prefix: String): List<String> {
        val permission = when (subcommand.lowercase(Locale.getDefault())) {
            "info" -> "booklite.admin.info"
            "read" -> "booklite.admin.read"
            "get" -> "booklite.admin.get"
            "delete" -> "booklite.admin.delete"
            else -> null
        }
        if (permission == null || !sender.hasPermission(permission)) {
            return emptyList()
        }
        return try {
            books.completeIds(prefix, TAB_ID_LIMIT)
        } catch (exception: SQLException) {
            books.logStorageFailure("tab complete", exception)
            emptyList()
        }
    }

    private fun filter(source: List<String>, prefix: String?): List<String> {
        val normalizedPrefix = prefix?.lowercase(Locale.getDefault()) ?: ""
        val out = ArrayList<String>()
        for (value in source) {
            if (value.lowercase(Locale.getDefault()).startsWith(normalizedPrefix)) {
                out.add(value)
            }
        }
        return out
    }

    private fun formatTime(millis: Long?): String =
        if (millis == null) "-" else Instant.ofEpochMilli(millis).toString()

    private companion object {
        const val LIST_PAGE_SIZE: Int = 8
        const val TAB_ID_LIMIT: Int = 20
        const val DAY_MILLIS: Long = 86_400_000L
        val ROOT_SUBS: List<String> = listOf(
            "help",
            "status",
            "reload",
            "convert",
            "restore",
            "info",
            "read",
            "get",
            "delete",
            "purge",
            "list",
            "restorecontainer",
        )
    }
}
