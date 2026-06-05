package org.cubexmc.clarity;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /clarity 命令:player / item / reload。目标支持玩家名或原版选择器(@a/@s/@p/@r)。 */
public final class ClarityCommand implements TabExecutor {

    private static final List<String> ROOT_SUBS = List.of("player", "item", "reload", "scan", "purge", "sweep");
    private static final List<String> PLAYER_SUBS = List.of("scan", "purge", "sweep");
    private static final List<String> ITEM_SUBS = List.of("scan", "sweep", "purge");
    private static final List<String> ITEM_SCOPES = List.of("hand", "inventory", "equipment", "ender", "all");
    private static final List<String> SELECTORS = List.of("@a", "@s", "@p", "@r");

    private final ClarityPlugin plugin;
    private final ClarityService service;

    public ClarityCommand(ClarityPlugin plugin, ClarityService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "player" -> handlePlayer(sender, args);
            case "item" -> handleItem(sender, args);
            case "reload" -> {
                plugin.reloadClarityConfig();
                sender.sendMessage("§aClarity config reloaded. auto-clean-on-join="
                        + plugin.config().autoCleanOnJoin() + " dry-run=" + plugin.config().dryRun());
            }
            case "scan", "sweep", "purge" -> handleLegacyPlayer(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            playerUsage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "scan" -> {
                for (Player t : resolveTargets(sender, args, 2)) {
                    service.scan(sender, t);
                }
            }
            case "sweep" -> {
                for (Player t : resolveTargets(sender, args, 2)) {
                    service.sweep(sender, t, 0L);
                }
            }
            case "purge" -> {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /clarity player purge <player|@selector> attr <namespace|id>  |  effect <type|all-infinite>");
                    return;
                }
                String kind = args[3].toLowerCase(Locale.ROOT);
                if (!kind.equals("attr") && !kind.equals("effect")) {
                    sender.sendMessage("§cUnknown kind '" + kind + "'. Use 'attr' or 'effect'.");
                    return;
                }
                for (Player t : resolveTargets(sender, args, 2)) {
                    if (kind.equals("attr")) {
                        service.purgeAttr(sender, t, args[4]);
                    } else {
                        service.purgeEffect(sender, t, args[4]);
                    }
                }
            }
            default -> playerUsage(sender);
        }
    }

    private void handleItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            itemUsage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "scan" -> {
                TargetScope targetScope = resolveTargetsAndScope(sender, args, 2, ItemScope.ALL);
                for (Player t : targetScope.targets()) {
                    service.scanItems(sender, t, targetScope.scope());
                }
            }
            case "sweep" -> {
                TargetScope targetScope = resolveTargetsAndScope(sender, args, 2, ItemScope.INVENTORY);
                for (Player t : targetScope.targets()) {
                    service.sweepItems(sender, t, targetScope.scope());
                }
            }
            case "purge" -> {
                ItemPurgeArgs purge = parseItemPurge(sender, args, 2);
                if (purge.targets().isEmpty()) {
                    return;
                }
                if (!purge.rule().equals("leveltools")) {
                    sender.sendMessage("§cUnknown item purge rule '" + purge.rule() + "'. Use 'leveltools'.");
                    return;
                }
                for (Player t : purge.targets()) {
                    service.purgeLevelToolsItems(sender, t, purge.scope());
                }
            }
            default -> itemUsage(sender);
        }
    }

    private void handleLegacyPlayer(CommandSender sender, String[] args) {
        sender.sendMessage("§eLegacy syntax is still supported. Prefer /clarity player "
                + args[0].toLowerCase(Locale.ROOT) + " ...");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "scan" -> {
                for (Player t : resolveTargets(sender, args, 1)) {
                    service.scan(sender, t);
                }
            }
            case "sweep" -> {
                for (Player t : resolveTargets(sender, args, 1)) {
                    service.sweep(sender, t, 0L);
                }
            }
            case "purge" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /clarity player purge <player|@selector> attr <namespace|id>  |  effect <type|all-infinite>");
                    return;
                }
                String kind = args[2].toLowerCase(Locale.ROOT);
                if (!kind.equals("attr") && !kind.equals("effect")) {
                    sender.sendMessage("§cUnknown kind '" + kind + "'. Use 'attr' or 'effect'.");
                    return;
                }
                for (Player t : resolveTargets(sender, args, 1)) {
                    if (kind.equals("attr")) {
                        service.purgeAttr(sender, t, args[3]);
                    } else {
                        service.purgeEffect(sender, t, args[3]);
                    }
                }
            }
            default -> usage(sender);
        }
    }

    private TargetScope resolveTargetsAndScope(CommandSender sender, String[] args, int idx, ItemScope defaultScope) {
        if (args.length <= idx) {
            if (sender instanceof Player self) {
                return new TargetScope(List.of(self), defaultScope);
            }
            sender.sendMessage("§cMissing target (player name or selector).");
            return TargetScope.empty(defaultScope);
        }
        if (args.length > idx + 2) {
            sender.sendMessage("§cToo many arguments.");
            itemUsage(sender);
            return TargetScope.empty(defaultScope);
        }

        ItemScope firstAsScope = ItemScope.parse(args[idx]);
        if (firstAsScope != null) {
            if (sender instanceof Player self) {
                return new TargetScope(List.of(self), firstAsScope);
            }
            sender.sendMessage("§cConsole must provide a target before the item scope.");
            return TargetScope.empty(firstAsScope);
        }

        List<Player> targets = resolveTargets(sender, args, idx);
        ItemScope scope = defaultScope;
        if (args.length > idx + 1) {
            scope = ItemScope.parse(args[idx + 1]);
            if (scope == null) {
                sender.sendMessage("§cUnknown item scope '" + args[idx + 1] + "'. Use hand, inventory, equipment, ender, or all.");
                return TargetScope.empty(defaultScope);
            }
        }
        return new TargetScope(targets, scope);
    }

    private ItemPurgeArgs parseItemPurge(CommandSender sender, String[] args, int idx) {
        if (args.length <= idx) {
            itemPurgeUsage(sender);
            return ItemPurgeArgs.empty();
        }
        String rule = args[args.length - 1].toLowerCase(Locale.ROOT);
        int beforeRule = args.length - idx - 1;
        if (beforeRule > 2) {
            itemPurgeUsage(sender);
            return ItemPurgeArgs.empty();
        }

        ItemScope scope = ItemScope.HAND;
        List<Player> targets;
        if (beforeRule == 0) {
            if (sender instanceof Player self) {
                targets = List.of(self);
            } else {
                sender.sendMessage("§cConsole must provide a target.");
                return ItemPurgeArgs.empty();
            }
        } else if (beforeRule == 1) {
            ItemScope onlyScope = ItemScope.parse(args[idx]);
            if (onlyScope != null) {
                if (sender instanceof Player self) {
                    targets = List.of(self);
                    scope = onlyScope;
                } else {
                    sender.sendMessage("§cConsole must provide a target before the item scope.");
                    return ItemPurgeArgs.empty();
                }
            } else {
                targets = resolveTargets(sender, args, idx);
            }
        } else {
            targets = resolveTargets(sender, args, idx);
            scope = ItemScope.parse(args[idx + 1]);
            if (scope == null) {
                sender.sendMessage("§cUnknown item scope '" + args[idx + 1] + "'. Use hand, inventory, equipment, ender, or all.");
                return ItemPurgeArgs.empty();
            }
        }
        return new ItemPurgeArgs(targets, scope, rule);
    }

    /** 解析目标:玩家精确名,或原版选择器(@a/@s/@p/@r/...);缺省时取命令发出者。 */
    private List<Player> resolveTargets(CommandSender sender, String[] args, int idx) {
        if (args.length <= idx) {
            if (sender instanceof Player self) {
                return List.of(self);
            }
            sender.sendMessage("§cMissing target (player name or selector).");
            return List.of();
        }
        String token = args[idx];
        if (token.startsWith("@")) {
            List<Player> players = new ArrayList<>();
            try {
                for (Entity e : Bukkit.selectEntities(sender, token)) {
                    if (e instanceof Player p) {
                        players.add(p);
                    }
                }
            } catch (IllegalArgumentException ex) {
                sender.sendMessage("§cInvalid selector: " + token);
                return List.of();
            }
            if (players.isEmpty()) {
                sender.sendMessage("§eSelector matched no online players: " + token);
            }
            return players;
        }
        Player p = Bukkit.getPlayerExact(token);
        if (p == null) {
            sender.sendMessage("§cPlayer not online: " + token + " §7(works on online players only)");
            return List.of();
        }
        return List.of(p);
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§6Clarity §7— clean orphaned player state and item metadata");
        playerUsage(sender);
        itemUsage(sender);
        sender.sendMessage("§e  /clarity reload §7— reload config.yml");
    }

    private void playerUsage(CommandSender sender) {
        sender.sendMessage("§e  /clarity player scan <player|@selector> §7— list attribute modifiers + potion effects");
        sender.sendMessage("§e  /clarity player sweep <player|@selector> §7— apply configured player blacklist (honours dry-run)");
        sender.sendMessage("§e  /clarity player purge <player|@selector> attr <namespace|id> §7— remove matching modifiers");
        sender.sendMessage("§e  /clarity player purge <player|@selector> effect <type|all-infinite> §7— remove potion effect(s)");
    }

    private void itemUsage(CommandSender sender) {
        sender.sendMessage("§e  /clarity item scan <player|@selector> [hand|inventory|equipment|ender|all] §7— list suspicious item metadata");
        sender.sendMessage("§e  /clarity item sweep <player|@selector> [hand|inventory|equipment|ender|all] §7— apply configured item rules (honours dry-run)");
        itemPurgeUsage(sender);
    }

    private void itemPurgeUsage(CommandSender sender) {
        sender.sendMessage("§e  /clarity item purge <player|@selector> [hand|inventory|equipment|ender|all] leveltools §7— remove LevelTools item residue");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT_SUBS, args[0]);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("player")) {
            return completePlayer(args);
        }
        if (root.equals("item")) {
            return completeItem(args);
        }
        if (args.length == 2 && (root.equals("scan") || root.equals("purge") || root.equals("sweep"))) {
            return filter(targetSuggestions(), args[1]);
        }
        if (args.length == 3 && root.equals("purge")) {
            return filter(List.of("attr", "effect"), args[2]);
        }
        if (args.length == 4 && root.equals("purge") && args[2].equalsIgnoreCase("effect")) {
            return filter(List.of("all-infinite"), args[3]);
        }
        return new ArrayList<>();
    }

    private List<String> completePlayer(String[] args) {
        if (args.length == 2) {
            return filter(PLAYER_SUBS, args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && PLAYER_SUBS.contains(sub)) {
            return filter(targetSuggestions(), args[2]);
        }
        if (args.length == 4 && sub.equals("purge")) {
            return filter(List.of("attr", "effect"), args[3]);
        }
        if (args.length == 5 && sub.equals("purge") && args[3].equalsIgnoreCase("effect")) {
            return filter(List.of("all-infinite"), args[4]);
        }
        return new ArrayList<>();
    }

    private List<String> completeItem(String[] args) {
        if (args.length == 2) {
            return filter(ITEM_SUBS, args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && ITEM_SUBS.contains(sub)) {
            List<String> suggestions = new ArrayList<>(targetSuggestions());
            suggestions.addAll(ITEM_SCOPES);
            if (sub.equals("purge")) {
                suggestions.add("leveltools");
            }
            return filter(suggestions, args[2]);
        }
        if (args.length == 4 && ITEM_SUBS.contains(sub)) {
            List<String> suggestions = new ArrayList<>(ITEM_SCOPES);
            if (sub.equals("purge")) {
                suggestions.add("leveltools");
            }
            return filter(suggestions, args[3]);
        }
        if (args.length == 5 && sub.equals("purge")) {
            return filter(List.of("leveltools"), args[4]);
        }
        return new ArrayList<>();
    }

    private List<String> targetSuggestions() {
        List<String> names = new ArrayList<>(SELECTORS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    private record TargetScope(List<Player> targets, ItemScope scope) {
        static TargetScope empty(ItemScope scope) {
            return new TargetScope(List.of(), scope);
        }
    }

    private record ItemPurgeArgs(List<Player> targets, ItemScope scope, String rule) {
        static ItemPurgeArgs empty() {
            return new ItemPurgeArgs(List.of(), ItemScope.HAND, "");
        }
    }
}
