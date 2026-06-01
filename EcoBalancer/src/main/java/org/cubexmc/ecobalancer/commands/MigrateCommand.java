package org.cubexmc.ecobalancer.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.utils.ConfigMigrator;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Command for manual configuration migration and backup.
 */
public class MigrateCommand implements CommandExecutor {
    private final EcoBalancer plugin;

    public MigrateCommand(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ecobalancer.admin")) {
            sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.getFormattedMessage("messages.migration.command_usage", null));
            return true;
        }

        String action = args[0].toLowerCase();
        ConfigMigrator migrator = new ConfigMigrator(plugin);

        switch (action) {
            case "check":
                boolean configNeeded = migrator.isConfigMigrationNeeded();
                String lang = plugin.getConfig().getString("language", "en_US");
                boolean langNeeded = migrator.isLangMigrationNeeded(lang);

                sender.sendMessage(plugin.getFormattedMessage("messages.migration.status_check", null));
                Map<String, String> configPh = new HashMap<>();
                configPh.put("status", configNeeded
                        ? "§cOutdated (v" + migrator.getCurrentConfigVersion() + " -> v" + ConfigMigrator.CURRENT_CONFIG_VERSION + ")"
                        : "§aUp to date (v" + ConfigMigrator.CURRENT_CONFIG_VERSION + ")");
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.status_config_line", configPh));
                Map<String, String> langPh = new HashMap<>();
                langPh.put("lang", lang);
                langPh.put("status", langNeeded
                        ? "§cOutdated (v" + migrator.getCurrentLangVersion(lang) + " -> v" + ConfigMigrator.CURRENT_LANG_VERSION + ")"
                        : "§aUp to date (v" + ConfigMigrator.CURRENT_LANG_VERSION + ")");
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.status_lang_line", langPh));

                if (configNeeded || langNeeded) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.hint_run", null));
                }
                break;

            case "run":
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_starting", null));
                boolean configMigrated = migrator.migrateConfig();
                String currentLang = plugin.getConfig().getString("language", "en_US");
                boolean langMigrated = migrator.migrateLanguageFile(currentLang);

                if (configMigrated) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_config_migrated", null));
                    // Reload to apply changes
                    plugin.reloadConfig();
                    plugin.loadConfiguration();
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_config_reloaded", null));
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_config_not_needed", null));
                }

                if (langMigrated) {
                    Map<String, String> migratedLangPh = new HashMap<>();
                    migratedLangPh.put("lang", currentLang);
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_lang_migrated", migratedLangPh));
                    // Reload lang (happens in loadConfiguration, but just in case)
                    // plugin.loadLangFile(); // method is private or part of loadConfiguration
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.run_lang_not_needed", null));
                }
                break;

            case "backup":
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.backup_starting", null));
                File configFile = new File(plugin.getDataFolder(), "config.yml");
                String langName = plugin.getConfig().getString("language", "en_US");
                File langFile = new File(plugin.getDataFolder(), "lang/" + langName + ".yml");

                migrator.createBackup(configFile, "config");
                if (langFile.exists()) {
                    migrator.createBackup(langFile, "lang_" + langName);
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.backup_done", null));
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.migration.backup_lang_missing", null));
                }
                break;

            default:
                sender.sendMessage(plugin.getFormattedMessage("messages.migration.command_usage", null));
                break;
        }

        return true;
    }
}
