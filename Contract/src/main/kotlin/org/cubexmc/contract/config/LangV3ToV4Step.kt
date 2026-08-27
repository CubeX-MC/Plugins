package org.cubexmc.contract.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep
import org.cubexmc.core.CubexPlugin
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Adds the SALE command/GUI strings without replacing operator-customized v3 values. */
class LangV3ToV4Step(private val plugin: CubexPlugin) : MigrationStep {
    override fun fromVersion(): Int = 3

    override fun toVersion(): Int = 4

    override fun description(): String = "Add SALE command, confirmation, claim, and wizard language keys."

    override fun migrate(context: MigrationContext) {
        val stream = plugin.getResource(context.resourcePath())
        if (stream == null) {
            context.warning(context.resourcePath(), "Bundled language resource missing from the jar; no SALE keys were added.")
            return
        }
        val bundled = stream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, StandardCharsets.UTF_8))
        }
        val yaml = context.yaml()
        var added = 0
        for (key in bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || yaml.contains(key)) continue
            yaml[key] = bundled.get(key)
            added++
        }
        if (added > 0) plugin.log().info("Added $added SALE language keys to ${context.resourcePath()}.")
    }
}
