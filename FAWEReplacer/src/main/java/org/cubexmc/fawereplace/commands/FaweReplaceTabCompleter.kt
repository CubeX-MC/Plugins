package org.cubexmc.fawereplace.commands

import java.util.Locale
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.EntityType

/**
 * 处理 /fawereplace 命令的 Tab 补全
 */
class FaweReplaceTabCompleter : TabCompleter {
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        // 只有拥有权限的玩家才能看到补全
        if (!sender.hasPermission("fawereplace.use")) {
            return emptyList()
        }

        // 第一个参数：子命令补全
        if (args.size == 1) {
            val input = args[0].lowercase(Locale.getDefault())
            return SUB_COMMANDS.filter { it.startsWith(input) }
        }

        // 第二个参数：根据不同子命令提供补全
        if (args.size == 2) {
            val subCommand = args[0].lowercase(Locale.getDefault())
            val input = args[1].uppercase(Locale.getDefault()) // Material is uppercase

            if (subCommand == "start") {
                return START_OPTIONS.filter { it.startsWith(input.lowercase(Locale.getDefault())) }
            } else if (subCommand == "addrule" || subCommand == "removerule") {
                // Return blocks + entities
                val suggestions = ArrayList<String>()
                for (material in Material.values()) {
                    if (material.isBlock) {
                        suggestions.add(material.name)
                    }
                }
                for (entityType in EntityType.values()) {
                    // Filter for relevant entity types (living, armor stand, item frame, painting,
                    // end crystal)
                    if (entityType.isAlive
                        || entityType == EntityType.ARMOR_STAND
                        || entityType == EntityType.ITEM_FRAME
                        || entityType == EntityType.PAINTING
                        || entityType == EntityType.ENDER_CRYSTAL
                    ) {
                        suggestions.add(entityType.name)
                    }
                }
                return suggestions.filter { it.startsWith(input) }
            }
        }

        // 第三个参数：addrule 的目标方块
        if (args.size == 3 && args[0].equals("addrule", ignoreCase = true)) {
            val input = args[2].uppercase(Locale.getDefault())
            return Material.values()
                .asSequence()
                .filter(Material::isBlock)
                .map(Material::name)
                .filter { it.startsWith(input) }
                .toList()
        }

        // 其他参数不提供补全
        return emptyList()
    }

    private companion object {
        val SUB_COMMANDS: List<String> = listOf(
            "start",
            "stop",
            "status",
            "reload",
            "help",
            "setregion",
            "addrule",
            "removerule",
            "rules",
        )
        val START_OPTIONS: List<String> = listOf("--fresh")
    }
}
