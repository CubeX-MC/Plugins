package org.cubexmc.fawereplace.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理 /fawereplace 命令的 Tab 补全
 */
public class FaweReplaceTabCompleter implements TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "start", "stop", "status", "reload", "help", "setregion", "addrule", "removerule", "rules");

    private static final List<String> START_OPTIONS = Arrays.asList(
            "--fresh");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 只有拥有权限的玩家才能看到补全
        if (!sender.hasPermission("fawereplace.use")) {
            return new ArrayList<>();
        }

        // 第一个参数：子命令补全
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(cmd -> cmd.startsWith(input))
                    .collect(Collectors.toList());
        }

        // 第二个参数：根据不同子命令提供补全
        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            String input = args[1].toUpperCase(); // Material is uppercase

            if (subCmd.equals("start")) {
                return START_OPTIONS.stream()
                        .filter(opt -> opt.startsWith(input.toLowerCase()))
                        .collect(Collectors.toList());
            } else if (subCmd.equals("addrule") || subCmd.equals("removerule")) {
                // Return blocks + entities
                List<String> suggestions = new ArrayList<>();
                for (Material m : Material.values()) {
                    if (m.isBlock())
                        suggestions.add(m.name());
                }
                for (EntityType et : EntityType.values()) {
                    // Filter for relevant entity types (living, armor stand, item frame, painting,
                    // end crystal)
                    if (et.isAlive() || et == EntityType.ARMOR_STAND || et == EntityType.ITEM_FRAME
                            || et == EntityType.PAINTING || et == EntityType.ENDER_CRYSTAL) {
                        suggestions.add(et.name());
                    }
                }
                return suggestions.stream()
                        .filter(name -> name.startsWith(input))
                        .collect(Collectors.toList());
            }
        }

        // 第三个参数：addrule 的目标方块
        if (args.length == 3 && args[0].equalsIgnoreCase("addrule")) {
            String input = args[2].toUpperCase();
            return Arrays.stream(org.bukkit.Material.values())
                    .filter(org.bukkit.Material::isBlock)
                    .map(org.bukkit.Material::name)
                    .filter(name -> name.startsWith(input))
                    .collect(Collectors.toList());
        }

        // 其他参数不提供补全
        return new ArrayList<>();
    }
}
