package org.cubexmc.contract.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep
import org.cubexmc.core.CubexPlugin
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Adds terminal lifecycle feedback while retaining operator edits. */
class LangV6ToV7Step(private val plugin: CubexPlugin) : MigrationStep {
    override fun fromVersion(): Int = 6
    override fun toVersion(): Int = 7
    override fun description(): String = "Add alliance settlement and recovery language keys."

    override fun migrate(context: MigrationContext) {
        val stream = plugin.getResource(context.resourcePath())
        if (stream == null) {
            context.warning(context.resourcePath(), "Bundled language resource missing; no settlement keys were added.")
            return
        }
        val bundled = stream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, StandardCharsets.UTF_8))
        }
        val yaml = context.yaml()
        for (key in bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(key) && !yaml.contains(key)) yaml[key] = bundled.get(key)
        }
    }
}
