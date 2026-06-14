package org.cubexmc.reputations.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.cubexmc.reputations.ReputationsPlugin
import org.cubexmc.reputations.gui.ReputationGui
import org.cubexmc.reputations.service.ReputationServiceImpl
import org.cubexmc.reputations.storage.ReputationStore
import org.cubexmc.reputations.util.Colors
import java.util.Locale
import java.util.UUID

class ReputationCommand(
    private val plugin: ReputationsPlugin,
    private val service: ReputationServiceImpl,
    private val store: ReputationStore,
    private val gui: ReputationGui,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) {
                store.cacheName(sender.uniqueId, sender.name)
                gui.open(sender, sender.uniqueId, sender.name)
            } else {
                send(sender, "&#FFE066用法: /reputation <玩家>")
            }
            return true
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "fields" -> listFields(sender)
            "set" -> mutate(sender, args, set = true)
            "add" -> mutate(sender, args, set = false)
            "reset" -> resetField(sender, args)
            "reload" -> reload(sender)
            else -> view(sender, args[0])
        }
    }

    private fun view(sender: CommandSender, name: String): Boolean {
        if (!sender.hasPermission("reputation.use")) {
            send(sender, "&#E63946你没有权限。")
            return true
        }
        val target = resolve(name)
        if (target == null) {
            send(sender, "&#E63946未找到玩家 $name 的信誉记录。")
            return true
        }
        if (sender is Player) {
            gui.open(sender, target.first, target.second)
        } else {
            printProfile(sender, target.first, target.second)
        }
        return true
    }

    private fun printProfile(sender: CommandSender, uuid: UUID, name: String) {
        send(sender, "&#F4D03F$name 的信誉档案:")
        val fields = service.fields().sortedWith(compareBy({ it.namespace() }, { it.id() }))
        if (fields.isEmpty()) {
            send(sender, "&#CFD8DC当前没有任何已注册的信誉字段。")
            return
        }
        for (field in fields) {
            send(sender, "&#CFD8DC${field.displayName()} &#9AA5B1(${field.key()})&#CFD8DC: &#FFFFFF${format(service.get(uuid, field.key()))}")
        }
    }

    private fun listFields(sender: CommandSender): Boolean {
        if (!sender.hasPermission("reputation.use")) {
            send(sender, "&#E63946你没有权限。")
            return true
        }
        val fields = service.fields().sortedWith(compareBy({ it.namespace() }, { it.id() }))
        if (fields.isEmpty()) {
            send(sender, "&#FFE066当前没有任何已注册的信誉字段。")
            return true
        }
        send(sender, "&#F4D03F已注册的信誉字段 (${fields.size}):")
        for (field in fields) {
            send(sender, "&#FFE066${field.key()} &#CFD8DC- &#FFFFFF${field.displayName()} &#9AA5B1(默认 ${format(field.defaultValue())})")
        }
        return true
    }

    private fun mutate(sender: CommandSender, args: Array<String>, set: Boolean): Boolean {
        if (!sender.hasPermission("reputation.admin")) {
            send(sender, "&#E63946你没有权限。")
            return true
        }
        if (args.size < 4) {
            send(sender, "&#FFE066用法: /reputation ${if (set) "set" else "add"} <玩家> <字段key> <数值>")
            return true
        }
        val field = service.field(args[2])
        if (field == null) {
            send(sender, "&#E63946未注册的字段 ${args[2]} (用 /reputation fields 查看)。")
            return true
        }
        val amount = args[3].toDoubleOrNull()
        if (amount == null || !amount.isFinite()) {
            send(sender, "&#E63946数值格式不正确。")
            return true
        }
        val (uuid, name) = resolveForce(args[1])
        store.cacheName(uuid, name)
        if (set) {
            service.set(uuid, field.key(), amount)
            send(sender, "&#69DB7C已设置 $name 的 ${field.key()} = ${format(amount)}")
        } else {
            val now = service.add(uuid, field.key(), amount)
            send(sender, "&#69DB7C已调整 $name 的 ${field.key()},现为 ${format(now)}")
        }
        return true
    }

    private fun resetField(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("reputation.admin")) {
            send(sender, "&#E63946你没有权限。")
            return true
        }
        if (args.size < 3) {
            send(sender, "&#FFE066用法: /reputation reset <玩家> <字段key>")
            return true
        }
        val field = service.field(args[2])
        if (field == null) {
            send(sender, "&#E63946未注册的字段 ${args[2]}。")
            return true
        }
        val (uuid, name) = resolveForce(args[1])
        service.reset(uuid, field.key())
        send(sender, "&#69DB7C已重置 $name 的 ${field.key()}。")
        return true
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("reputation.admin")) {
            send(sender, "&#E63946你没有权限。")
            return true
        }
        plugin.reloadConfig()
        send(sender, "&#69DB7CReputations 配置已重载。")
        return true
    }

    private fun resolve(name: String): Pair<UUID, String>? {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId to it.name }
        val cached = store.findByName(name) ?: return null
        return cached to (store.nameOf(cached) ?: name)
    }

    private fun resolveForce(name: String): Pair<UUID, String> {
        resolve(name)?.let { return it }
        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(name)
        return offline.uniqueId to (offline.name ?: name)
    }

    private fun format(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else value.toString()

    private fun send(sender: CommandSender, message: String) {
        sender.sendMessage(Colors.color(message))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            val options = ArrayList<String>()
            options.addAll(listOf("fields", "set", "add", "reset", "reload"))
            Bukkit.getOnlinePlayers().forEach { options.add(it.name) }
            return options.filter { it.lowercase(Locale.ROOT).startsWith(args[0].lowercase(Locale.ROOT)) }
        }
        val sub = args[0].lowercase(Locale.ROOT)
        if (args.size == 2 && sub in listOf("set", "add", "reset")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase(Locale.ROOT).startsWith(args[1].lowercase(Locale.ROOT)) }
        }
        if (args.size == 3 && sub in listOf("set", "add", "reset")) {
            return service.fields().map { it.key() }.filter { it.lowercase(Locale.ROOT).startsWith(args[2].lowercase(Locale.ROOT)) }
        }
        return emptyList()
    }
}
