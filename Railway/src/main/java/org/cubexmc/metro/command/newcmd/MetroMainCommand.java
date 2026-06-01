package org.cubexmc.metro.command.newcmd;

import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.manager.LineManager;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.update.ConfigUpdater;
import org.cubexmc.metro.update.DataFileUpdater;
import org.cubexmc.metro.util.OwnershipUtil;

public class MetroMainCommand {

    private final Metro plugin;
    private final LineManager lineManager;
    private final StopManager stopManager;

    public MetroMainCommand(Metro plugin, LineManager lineManager, StopManager stopManager) {
        this.plugin = plugin;
        this.lineManager = lineManager;
        this.stopManager = stopManager;
    }

    @Command("rw|railway|rail")
    @CommandDescription("Railway Main Command")
    public void root(CommandSender sender) {
        help(sender);
    }

    @Command("rw|railway|rail help")
    @CommandDescription("Show Metro Help Menu")
    public void help(CommandSender sender) {
        org.cubexmc.metro.manager.LanguageManager lang = plugin.getLanguageManager();
        sender.sendMessage(lang.getMessage("command.help_header"));
        sender.sendMessage(lang.getMessage("command.help_gui"));
        sender.sendMessage(lang.getMessage("command.help_reload"));
        sender.sendMessage(lang.getMessage("command.help_line"));
        sender.sendMessage(lang.getMessage("command.help_stop"));
        sender.sendMessage(lang.getMessage("command.help_portal"));
    }

    @Command("rw|railway|rail gui")
    @CommandDescription("Open the Metro GUI")
    @Permission("railway.gui")
    public void gui(Player player) {
        plugin.getGuiManager().openMainMenu(player);
    }

    @Command("rw|railway|rail reload")
    @CommandDescription("Reload Metro configuration")
    @Permission("railway.admin")
    public void reload(CommandSender sender) {
        if (sender instanceof Player player) {
            if (!OwnershipUtil.hasAdminBypass(player)) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("plugin.no_permission"));
                return;
            }
        }

        plugin.flushPersistentData();
        plugin.ensureDefaultConfigs();
        plugin.reloadConfig();
        ConfigUpdater.applyDefaults(plugin, "config.yml");
        ConfigUpdater.applyDefaultsToFile(plugin, "entity.yml");
        plugin.getConfigFacade().reload();
        DataFileUpdater.migrateAll(plugin);
        lineManager.reload();
        stopManager.reload();
        if (plugin.getPortalManager() != null) {
            plugin.getPortalManager().load();
        }
        if (plugin.getRailProtectionManager() != null) {
            plugin.getRailProtectionManager().rebuildAll();
        }
        plugin.getLanguageManager().loadLanguages();

        if (plugin.getLineServiceManager() != null) {
            plugin.getLineServiceManager().rebuildFromLines();
        }
        if (plugin.getEntityModelController() != null) {
            plugin.getEntityModelController().reload();
        }

        plugin.refreshMapIntegrations();

        sender.sendMessage(plugin.getLanguageManager().getMessage("plugin.reload"));
    }
}
