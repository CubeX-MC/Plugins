package org.cubexmc.mountlicense.command

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.model.VehicleRecord
import org.cubexmc.mountlicense.service.ParkingService.ActionResult
import org.cubexmc.mountlicense.service.ReindexResult
import java.time.Instant
import java.util.Locale
import java.util.UUID

class MountLicenseCommand(private val plugin: MountLicensePlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || "help".equals(args[0], ignoreCase = true)) {
            if (!requirePermission(sender, USE_PERMISSION)) return true
            sendHelp(sender)
            return true
        }
        val sub = args[0].lowercase(Locale.getDefault())
        if (requiresUsePermission(sub) && !requirePermission(sender, USE_PERMISSION)) {
            return true
        }
        return when (sub) {
            "list" -> handleList(sender)
            "info" -> handleInfo(sender, args)
            "park" -> handleParkAction(sender, "park")
            "unpark" -> handleParkAction(sender, "unpark")
            "lock" -> handleParkAction(sender, "lock")
            "unlock" -> handleParkAction(sender, "unlock")
            "release" -> handleParkAction(sender, "release")
            "recall" -> handleRecall(sender, args)
            "locate" -> handleLocate(sender, args)
            "key" -> handleKey(sender, args)
            "trust" -> handleTrust(sender, args, true)
            "untrust" -> handleTrust(sender, args, false)
            "admin" -> handleAdmin(sender, args)
            else -> {
                val p = HashMap<String, String>()
                p["input"] = args[0]
                lang().send(sender, "commands.unknown_subcommand", p)
                true
            }
        }
    }

    private fun requirePermission(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission)) return true
        lang().send(sender, "commands.no_permission")
        return false
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(lang().msg("commands.header"))
        sender.sendMessage(lang().msg("commands.help.line_list"))
        sender.sendMessage(lang().msg("commands.help.line_info"))
        if (sender.hasPermission("mountlicense.park")) {
            sender.sendMessage(lang().msg("commands.help.line_park"))
            sender.sendMessage(lang().msg("commands.help.line_unpark"))
            sender.sendMessage(lang().msg("commands.help.line_lock"))
            sender.sendMessage(lang().msg("commands.help.line_unlock"))
            sender.sendMessage(lang().msg("commands.help.line_release"))
        }
        if (sender.hasPermission("mountlicense.key.use")) {
            sender.sendMessage(lang().msg("commands.help.line_recall"))
            sender.sendMessage(lang().msg("commands.help.line_locate"))
            sender.sendMessage(lang().msg("commands.help.line_key_unbind"))
        }
        if (sender.hasPermission("mountlicense.trust")) {
            sender.sendMessage(lang().msg("commands.help.line_trust"))
            sender.sendMessage(lang().msg("commands.help.line_untrust"))
        }
        if (sender.hasPermission("mountlicense.admin.inspect")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_inspect"))
        }
        if (sender.hasPermission("mountlicense.admin.give")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_give"))
        }
        if (sender.hasPermission("mountlicense.admin.reindex")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_reindex"))
        }
        if (sender.hasPermission("mountlicense.admin.reload")) {
            sender.sendMessage(lang().msg("commands.help.line_admin_reload"))
        }
    }

    private fun handleParkAction(sender: CommandSender, action: String): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        if (!player.hasPermission("mountlicense.park")) {
            lang().send(player, "commands.no_permission")
            return true
        }
        val reach = plugin.configManager().getMaxInteractDistance() + 4.0
        val target = plugin.parkingService().findTargeted(player, reach)
        if (target == null) {
            lang().send(player, "park.no_target")
            return true
        }

        val result = when (action) {
            "park" -> plugin.parkingService().park(player, target)
            "unpark" -> plugin.parkingService().unpark(player, target)
            "lock" -> plugin.parkingService().lock(player, target)
            "unlock" -> plugin.parkingService().unlock(player, target)
            "release" -> plugin.parkingService().release(player, target)
            else -> return true
        }

        val p = HashMap<String, String>()
        p["entity_type"] = target.type.name
        when (result) {
            ActionResult.SUCCESS -> lang().send(player, "park.${action}_success", p)
            ActionResult.NOT_REGISTERED -> lang().send(player, "park.not_registered", p)
            ActionResult.NOT_OWNER -> lang().send(player, "park.not_owner", p)
            ActionResult.ALREADY_IN_STATE -> lang().send(player, "park.already_in_target_state", p)
            ActionResult.CONFIRM_REQUIRED -> lang().send(player, "park.release_confirm", p)
            ActionResult.CONFIRMED -> lang().send(player, "park.release_done", p)
        }
        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        val records = plugin.vehicleIndex().byOwner(player.uniqueId)
        if (records.isEmpty()) {
            lang().send(player, "list.empty")
            return true
        }
        val header = HashMap<String, String>()
        header["count"] = records.size.toString()
        lang().send(player, "list.header", header)

        for (rec in records) {
            val p = HashMap<String, String>()
            rec.profile()?.let { p["profile"] = it }
            p["short_id"] = rec.shortId()
            p["state"] = rec.state().name
            p["world"] = rec.world() ?: "?"
            player.sendMessage(lang().msg("list.line", p))
        }
        return true
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        val rec = resolveVehicle(args[1])
        if (rec == null) {
            lang().send(player, "info.not_found")
            return true
        }
        if (rec.ownerUuid() != player.uniqueId && !player.hasPermission("mountlicense.admin.inspect")) {
            lang().send(player, "info.not_owner")
            return true
        }
        sendVehicleInfo(player, rec)
        return true
    }

    private fun sendVehicleInfo(to: CommandSender, rec: VehicleRecord) {
        val owner: OfflinePlayer = Bukkit.getOfflinePlayer(rec.ownerUuid())
        val ownerName = owner.name ?: lang().msg("general.unknown_player")
        val lastSeen = if (rec.lastSeenAt() <= 0) {
            lang().msg("general.never")
        } else {
            Instant.ofEpochMilli(rec.lastSeenAt()).toString()
        }

        to.sendMessage(lang().msg("info.header"))
        val p = HashMap<String, String>()
        p["vehicle_id"] = rec.shortId()
        to.sendMessage(lang().msg("info.line_id", p))
        p.clear()
        rec.profile()?.let { p["profile"] = it }
        to.sendMessage(lang().msg("info.line_profile", p))
        p.clear()
        rec.entityType()?.let { p["entity_type"] = it }
        to.sendMessage(lang().msg("info.line_entity", p))
        p.clear()
        p["owner"] = ownerName
        to.sendMessage(lang().msg("info.line_owner", p))
        p.clear()
        p["state"] = rec.state().name
        to.sendMessage(lang().msg("info.line_state", p))
        if (rec.world() != null) {
            p.clear()
            p["world"] = rec.world() ?: ""
            p["x"] = String.format("%.1f", rec.x())
            p["y"] = String.format("%.1f", rec.y())
            p["z"] = String.format("%.1f", rec.z())
            to.sendMessage(lang().msg("info.line_location", p))
        }
        p.clear()
        p["last_seen"] = lastSeen
        to.sendMessage(lang().msg("info.line_last_seen", p))

        p.clear()
        p["trustees"] = formatTrustees(rec)
        to.sendMessage(lang().msg("info.line_trustees", p))
    }

    private fun formatTrustees(rec: VehicleRecord): String {
        if (rec.trustees().isEmpty()) {
            return lang().msg("info.trustees_none")
        }
        val sb = StringBuilder()
        synchronized(rec.trustees()) {
            for (id in rec.trustees()) {
                if (sb.isNotEmpty()) sb.append(", ")
                val op = Bukkit.getOfflinePlayer(id)
                val name = op.name
                sb.append(name ?: id.toString().substring(0, 8))
            }
        }
        return sb.toString()
    }

    private fun handleRecall(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        if (!player.hasPermission("mountlicense.key.use")) {
            lang().send(player, "commands.no_permission")
            return true
        }
        var vehicleId: UUID? = null
        if (args.size >= 2) {
            val record = resolveVehicle(args[1])
            if (record == null) {
                lang().send(player, "recall.fail_not_found")
                return true
            }
            vehicleId = record.vehicleId()
        }
        val result = if (vehicleId == null) {
            plugin.recallService().recallNearest(player)
        } else {
            plugin.recallService().recallById(player, vehicleId)
        }
        plugin.recallService().sendResultMessage(player, result, vehicleId)
        return true
    }

    private fun handleLocate(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        val record = resolveVehicle(args[1])
        if (record == null) {
            lang().send(player, "info.not_found")
            return true
        }
        if (record.ownerUuid() != player.uniqueId && !player.hasPermission("mountlicense.admin.bypass")) {
            lang().send(player, "info.not_owner")
            return true
        }
        val info = plugin.recallService().locate(player, record.vehicleId())
        val p = HashMap<String, String>()
        p["short_id"] = record.shortId()
        record.profile()?.let { p["profile"] = it }
        if (record.world() != null) {
            p["world"] = record.world() ?: ""
            p["x"] = String.format("%.0f", record.x())
            p["y"] = String.format("%.0f", record.y())
            p["z"] = String.format("%.0f", record.z())
        } else {
            p["world"] = "?"
            p["x"] = "?"
            p["y"] = "?"
            p["z"] = "?"
        }
        lang().send(player, if (info.loaded()) "locate.loaded" else "locate.unloaded", p)
        return true
    }

    private fun handleKey(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        if (args.size < 2 || !"unbind".equals(args[1], ignoreCase = true)) {
            sendHelp(sender)
            return true
        }
        if (!player.hasPermission("mountlicense.key.use")) {
            lang().send(player, "commands.no_permission")
            return true
        }
        val hand = player.inventory.itemInMainHand
        if (!plugin.itemFactory().isKey(hand)) {
            lang().send(player, "key.unbind_no_key")
            return true
        }
        val changed = plugin.itemFactory().unbindKey(hand)
        if (changed) {
            player.inventory.setItemInMainHand(hand)
            lang().send(player, "key.unbind_success")
        } else {
            lang().send(player, "key.unbind_already_unbound")
        }
        return true
    }

    private fun handleTrust(sender: CommandSender, args: Array<out String>, add: Boolean): Boolean {
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        if (!player.hasPermission("mountlicense.trust")) {
            lang().send(player, "commands.no_permission")
            return true
        }
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }

        val target = Bukkit.getPlayerExact(args[1])
        if (target == null) {
            val p = HashMap<String, String>()
            p["player"] = args[1]
            lang().send(player, "commands.player_not_found", p)
            return true
        }
        if (target.uniqueId == player.uniqueId) {
            lang().send(player, "trust.cannot_trust_self")
            return true
        }

        val reach = plugin.configManager().getMaxInteractDistance() + 4.0
        val targetEntity = plugin.parkingService().findTargeted(player, reach)
        if (targetEntity == null) {
            lang().send(player, "trust.no_target")
            return true
        }
        val vehicleId = plugin.ownershipService().readVehicleId(targetEntity)
        if (vehicleId == null) {
            lang().send(player, "trust.target_not_registered")
            return true
        }
        if (!plugin.ownershipService().isOwner(targetEntity, player.uniqueId) &&
            !player.hasPermission("mountlicense.admin.bypass")
        ) {
            lang().send(player, "trust.not_owner")
            return true
        }
        val record = plugin.vehicleIndex().byId(vehicleId)
        if (record == null) {
            lang().send(player, "trust.target_not_registered")
            return true
        }

        val p = HashMap<String, String>()
        p["player"] = target.name
        p["entity_type"] = targetEntity.type.name
        p["short_id"] = record.shortId()

        if (add) {
            val max = plugin.configManager().getMaxTrusteesPerVehicle()
            synchronized(record.trustees()) {
                if (record.isTrustee(target.uniqueId)) {
                    lang().send(player, "trust.already_trusted", p)
                    return true
                }
                if (max >= 0 && record.trustees().size >= max) {
                    p["max"] = max.toString()
                    lang().send(player, "trust.fail_max", p)
                    return true
                }
                record.addTrustee(target.uniqueId)
            }
            plugin.vehicleIndex().markDirty()
            lang().send(player, "trust.added", p)
            if (target.isOnline) {
                lang().send(target, "trust.notify_added", p)
            }
        } else {
            synchronized(record.trustees()) {
                val removed = record.removeTrustee(target.uniqueId)
                if (!removed) {
                    lang().send(player, "trust.not_a_trustee", p)
                    return true
                }
            }
            plugin.vehicleIndex().markDirty()
            lang().send(player, "trust.removed", p)
            if (target.isOnline) {
                lang().send(target, "trust.notify_removed", p)
            }
        }
        return true
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            sendHelp(sender)
            return true
        }
        return when (val adminSub = args[1].lowercase(Locale.getDefault())) {
            "inspect" -> handleAdminInspect(sender)
            "give" -> handleAdminGive(sender, args)
            "reindex" -> handleAdminReindex(sender)
            "reload" -> handleAdminReload(sender)
            else -> {
                val p = HashMap<String, String>()
                p["input"] = adminSub
                lang().send(sender, "commands.unknown_subcommand", p)
                true
            }
        }
    }

    private fun handleAdminInspect(sender: CommandSender): Boolean {
        if (!sender.hasPermission("mountlicense.admin.inspect")) {
            lang().send(sender, "commands.no_permission")
            return true
        }
        val player = sender as? Player
        if (player == null) {
            lang().send(sender, "commands.player_only")
            return true
        }
        val reach = plugin.configManager().getMaxInteractDistance() + 4.0
        val ray = player.world.rayTraceEntities(player.eyeLocation, player.eyeLocation.direction, reach) { e ->
            e !== player
        }
        val target: Entity? = ray?.hitEntity
        if (target == null) {
            lang().send(player, "admin.inspect.no_target")
            return true
        }
        val vehicleId = plugin.registryService().readVehicleId(target)
        if (vehicleId == null) {
            val p = HashMap<String, String>()
            p["entity_type"] = target.type.name
            lang().send(player, "admin.inspect.target_unregistered", p)
            return true
        }
        val rec = plugin.vehicleIndex().byId(vehicleId)
        val p = HashMap<String, String>()
        p["vehicle_id"] = rec?.shortId() ?: vehicleId.toString()
        lang().send(player, "admin.inspect.target_registered", p)
        if (rec != null) sendVehicleInfo(player, rec)
        return true
    }

    private fun handleAdminGive(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mountlicense.admin.give")) {
            lang().send(sender, "commands.no_permission")
            return true
        }
        if (args.size < 4) {
            sendHelp(sender)
            return true
        }
        val target = Bukkit.getPlayerExact(args[2])
        if (target == null) {
            val p = HashMap<String, String>()
            p["player"] = args[2]
            lang().send(sender, "commands.player_not_found", p)
            return true
        }
        val kind = args[3].lowercase(Locale.getDefault())
        val amount = if (args.size >= 5) safeParseInt(args[4], 1) else 1
        val item: ItemStack = when (kind) {
            "license" -> plugin.itemFactory().createLicense(amount)
            "key" -> plugin.itemFactory().createKey(amount)
            else -> {
                val p = HashMap<String, String>()
                p["input"] = kind
                lang().send(sender, "commands.unknown_subcommand", p)
                return true
            }
        }
        val overflow = target.inventory.addItem(item)
        for (leftover in overflow.values) {
            target.world.dropItemNaturally(target.location, leftover)
        }
        val p = HashMap<String, String>()
        p["player"] = target.name
        p["amount"] = amount.toString()
        lang().send(sender, "admin.give.success", p)
        lang().send(target, "admin.give.received", p)
        return true
    }

    private fun handleAdminReindex(sender: CommandSender): Boolean {
        if (!sender.hasPermission("mountlicense.admin.reindex")) {
            lang().send(sender, "commands.no_permission")
            return true
        }
        lang().send(sender, "admin.reindex.started")
        val result: ReindexResult = plugin.registryService().reindexLoadedEntities()
        val p = HashMap<String, String>()
        p["scanned"] = result.scanned().toString()
        p["recovered"] = result.recovered().toString()
        lang().send(sender, "admin.reindex.finished", p)
        return true
    }

    private fun handleAdminReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("mountlicense.admin.reload")) {
            lang().send(sender, "commands.no_permission")
            return true
        }
        plugin.reloadAll()
        lang().send(sender, "admin.reload.success")
        return true
    }

    private fun resolveVehicle(input: String?): VehicleRecord? {
        if (input.isNullOrEmpty()) return null
        return try {
            plugin.vehicleIndex().byId(UUID.fromString(input))
        } catch (ex: IllegalArgumentException) {
            val prefix = input.lowercase(Locale.getDefault())
            for (rec in plugin.vehicleIndex().all()) {
                if (rec.shortId().lowercase(Locale.getDefault()).startsWith(prefix) ||
                    rec.vehicleId().toString().lowercase(Locale.getDefault()).startsWith(prefix)
                ) {
                    return rec
                }
            }
            null
        }
    }

    private fun safeParseInt(raw: String, fallback: Int): Int =
        try {
            val v = raw.toInt()
            if (v > 0) v else fallback
        } catch (ex: NumberFormatException) {
            fallback
        }

    private fun lang(): LanguageManager = plugin.languageManager()

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return filter(ROOT_SUBS, args[0])
        }
        if (args.size == 2 && "admin".equals(args[0], ignoreCase = true)) {
            return filter(ADMIN_SUBS, args[1])
        }
        if (args.size == 2 && (
                "info".equals(args[0], ignoreCase = true) ||
                    "recall".equals(args[0], ignoreCase = true) ||
                    "locate".equals(args[0], ignoreCase = true)
                )
        ) {
            if (sender is Player) {
                val ids = plugin.vehicleIndex().byOwner(sender.uniqueId).map { it.shortId() }
                return filter(ids, args[1])
            }
        }
        if (args.size == 2 && "key".equals(args[0], ignoreCase = true)) {
            return filter(KEY_SUBS, args[1])
        }
        if (args.size == 2 && (
                "trust".equals(args[0], ignoreCase = true) ||
                    "untrust".equals(args[0], ignoreCase = true)
                )
        ) {
            val names = Bukkit.getOnlinePlayers().map { it.name }
            return filter(names, args[1])
        }
        if (args.size == 3 && "admin".equals(args[0], ignoreCase = true) &&
            "give".equals(args[1], ignoreCase = true)
        ) {
            val names = Bukkit.getOnlinePlayers().map { it.name }
            return filter(names, args[2])
        }
        if (args.size == 4 && "admin".equals(args[0], ignoreCase = true) &&
            "give".equals(args[1], ignoreCase = true)
        ) {
            return filter(ITEM_KINDS, args[3])
        }
        return emptyList()
    }

    companion object {
        private const val USE_PERMISSION: String = "mountlicense.use"
        private val ROOT_SUBS: List<String> = listOf(
            "help",
            "list",
            "info",
            "park",
            "unpark",
            "lock",
            "unlock",
            "release",
            "recall",
            "locate",
            "key",
            "trust",
            "untrust",
            "admin",
        )
        private val ADMIN_SUBS: List<String> = listOf("inspect", "give", "reindex", "reload")
        private val KEY_SUBS: List<String> = listOf("unbind")
        private val ITEM_KINDS: List<String> = listOf("license", "key")

        @JvmStatic
        fun requiresUsePermission(subcommand: String?): Boolean {
            if (subcommand == null) return false
            return when (subcommand.lowercase(Locale.getDefault())) {
                "help", "list", "info", "locate" -> true
                else -> false
            }
        }

        private fun filter(source: List<String>?, prefix: String?): List<String> {
            if (source.isNullOrEmpty()) return emptyList()
            val p = prefix?.lowercase(Locale.getDefault()) ?: ""
            val out = ArrayList<String>()
            for (s in source) {
                if (s.lowercase(Locale.getDefault()).startsWith(p)) out.add(s)
            }
            return out
        }
    }
}
