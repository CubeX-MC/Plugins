package org.cubexmc.metro.command.newcmd

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.config.MigrationException
import org.cubexmc.metro.Metro
import org.cubexmc.metro.manager.LineManager
import org.cubexmc.metro.manager.StopManager
import org.cubexmc.metro.update.DataFileUpdater
import org.cubexmc.metro.update.MetroMigrations
import org.cubexmc.metro.util.OwnershipUtil
import org.incendo.cloud.annotations.Command
import org.incendo.cloud.annotations.CommandDescription
import org.incendo.cloud.annotations.Permission

class MetroMainCommand(
    private val plugin: Metro,
    lineManager: LineManager?,
    stopManager: StopManager?,
) {

    private val lineManagerRef: LineManager? = lineManager
    private val stopManagerRef: StopManager? = stopManager

    private val lineManager: LineManager
        get() = lineManagerRef ?: throw NullPointerException("lineManager")

    private val stopManager: StopManager
        get() = stopManagerRef ?: throw NullPointerException("stopManager")

    @Command("rw|railway|rail")
    @CommandDescription("Railway Main Command")
    fun root(sender: CommandSender) {
        help(sender)
    }

    @Command("rw|railway|rail help")
    @CommandDescription("Show Metro Help Menu")
    fun help(sender: CommandSender) {
        val lang = plugin.languageManager
        sender.sendMessage(lang.getMessage("command.help_header"))
        sender.sendMessage(lang.getMessage("command.help_gui"))
        sender.sendMessage(lang.getMessage("command.help_reload"))
        sender.sendMessage(lang.getMessage("command.help_line"))
        sender.sendMessage(lang.getMessage("command.help_stop"))
        sender.sendMessage(lang.getMessage("command.help_portal"))
    }

    @Command("rw|railway|rail gui")
    @CommandDescription("Open the Metro GUI")
    @Permission("railway.gui")
    fun gui(player: Player) {
        plugin.guiManager.openMainMenu(player)
    }

    @Command("rw|railway|rail reload")
    @CommandDescription("Reload Metro configuration")
    @Permission("railway.admin")
    fun reload(sender: CommandSender) {
        if (sender is Player && !OwnershipUtil.hasAdminBypass(sender)) {
            sender.sendMessage(plugin.languageManager.getMessage("plugin.no_permission"))
            return
        }

        plugin.flushPersistentData()
        plugin.ensureDefaultConfigs()
        try {
            MetroMigrations.migrateConfig(plugin)
            MetroMigrations.ensureEntityDefaults(plugin)
            MetroMigrations.ensureLanguageResources(plugin)
            MetroMigrations.migrateBundledLanguages(plugin)
        } catch (ex: MigrationException) {
            plugin.logger.warning("Railway reload aborted: configuration migration failed: " + ex.message)
            sender.sendMessage("§cRailway reload aborted: configuration migration failed.")
            return
        }
        plugin.reloadConfig()
        plugin.configFacade.reload()
        DataFileUpdater.migrateAll(plugin)
        lineManager.reload()
        stopManager.reload()
        plugin.portalManager?.load()
        plugin.railProtectionManager?.rebuildAll()
        plugin.languageManager.loadLanguages()

        plugin.lineServiceManager?.rebuildFromLines()
        plugin.entityModelController?.reload()

        plugin.refreshMapIntegrations()

        sender.sendMessage(plugin.languageManager.getMessage("plugin.reload"))
    }
}
