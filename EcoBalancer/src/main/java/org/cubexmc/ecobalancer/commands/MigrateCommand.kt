package org.cubexmc.ecobalancer.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.utils.ConfigMigrator
import java.io.File
import java.util.Locale

class MigrateCommand(private val plugin: EcoBalancer) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("ecobalancer.admin")) {
            sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null))
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.migration.command_usage", null))
            return true
        }

        val action = args[0].lowercase(Locale.getDefault())
        val migrator = ConfigMigrator(plugin)

        when (action) {
            "check" -> {
                val configNeeded = migrator.isConfigMigrationNeeded()
                val lang = plugin.config.getString("language", "en_US") ?: "en_US"
                val langNeeded = migrator.isLangMigrationNeeded(lang)

                sender.sendMessage(plugin.getFormattedMessage("messages.migration.status_check", null))
                val configPh: MutableMap<String, String> = HashMap()
                configPh["status"] = if (configNeeded) {
                    "§cOutdated (v${migrator.getCurrentConfigVersion()} -> v${ConfigMigrator.CURRENT_CONFIG_VERSION})"
                } else {
                    "§aUp to date (v${ConfigMigrator.CURRENT_CONFIG_VERSION})"
                }
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.status_config_line", configPh))
                val langPh: MutableMap<String, String> = HashMap()
                langPh["lang"] = lang
                langPh["status"] = if (langNeeded) {
                    "§cOutdated (v${migrator.getCurrentLangVersion(lang)} -> v${ConfigMigrator.CURRENT_LANG_VERSION})"
                } else {
                    "§aUp to date (v${ConfigMigrator.CURRENT_LANG_VERSION})"
                }
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.status_lang_line", langPh))

                if (configNeeded || langNeeded) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.hint_run", null))
                }
            }
            "run" -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_starting", null))
                val configMigrated = migrator.migrateConfig()
                val currentLang = plugin.config.getString("language", "en_US") ?: "en_US"
                val langMigrated = migrator.migrateLanguageFile(currentLang)

                if (configMigrated) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_config_migrated", null))
                    plugin.reloadConfig()
                    plugin.loadConfiguration()
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_config_reloaded", null))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_config_not_needed", null))
                }

                if (langMigrated) {
                    val migratedLangPh: MutableMap<String, String> = HashMap()
                    migratedLangPh["lang"] = currentLang
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_lang_migrated", migratedLangPh))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_lang_not_needed", null))
                }
            }
            "backup" -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.backup_starting", null))
                val configFile = File(plugin.dataFolder, "config.yml")
                val langName = plugin.config.getString("language", "en_US") ?: "en_US"
                val langFile = File(plugin.dataFolder, "lang/$langName.yml")

                migrator.createBackup(configFile, "config")
                if (langFile.exists()) {
                    migrator.createBackup(langFile, "lang_$langName")
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.backup_done", null))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.backup_lang_missing", null))
                }
            }
            else -> sender.sendMessage(plugin.getFormattedMessage("messages.migration.command_usage", null))
        }

        return true
    }
}
