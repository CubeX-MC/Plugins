package org.cubexmc.contract.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexPlugin
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * lang v2 -> v3. Two things changed at once, and a v2 file needs both or it renders wrongly:
 *
 * 1. **Every section now goes through the shared i18n service.** In v2 only `messages.*` did;
 *    `ui.*` and the enum label sections were read from a raw `YamlConfiguration` and coloured with
 *    the legacy `&#RRGGBB` form. Those values are now MiniMessage, so any legacy code left in an
 *    operator's file would print literally. Conversion uses
 *    [LegacyTextToMiniMessageStep.AngleBrackets.PRESERVE] because v2 already used `<name>`
 *    placeholders in those sections — escaping them would turn live placeholders into literal text.
 *
 * 2. **The GUI, wizard and service-layer failure reasons were externalized**, adding several
 *    hundred keys a v2 file has never seen. Missing keys are copied from the bundled resource.
 *
 * Values the operator already customised are converted but never replaced.
 */
class LangV2ToV3Step(private val plugin: CubexPlugin) : MigrationStep {

    private val converter = LegacyTextToMiniMessageStep(
        2,
        3,
        LegacyTextToMiniMessageStep.AngleBrackets.PRESERVE,
    )

    override fun fromVersion(): Int = 2

    override fun toVersion(): Int = 3

    override fun description(): String =
        "Convert every language section to MiniMessage and add the externalized UI keys."

    override fun migrate(context: MigrationContext) {
        val yaml = context.yaml()
        convertSection(yaml, yaml)
        mergeBundledKeys(context, yaml)
    }

    /** Rewrites legacy colour codes in place, skipping `messages.*`, which was already MiniMessage in v2. */
    private fun convertSection(root: YamlConfiguration, section: ConfigurationSection) {
        for (key in section.getKeys(false)) {
            val child = section.getConfigurationSection(key)
            if (child != null) {
                if (child.currentPath == MESSAGES_SECTION) continue
                convertSection(root, child)
                continue
            }
            if (section.currentPath?.startsWith(MESSAGES_SECTION) == true) continue
            val value = section.getString(key) ?: continue
            section.set(key, converter.convert(value))
        }
    }

    private fun mergeBundledKeys(context: MigrationContext, yaml: YamlConfiguration) {
        val resourcePath = context.resourcePath()
        val stream = plugin.getResource(resourcePath)
        if (stream == null) {
            context.warning(resourcePath, "Bundled language resource missing from the jar; no keys were added.")
            return
        }
        val bundled = stream.use { input ->
            YamlConfiguration.loadConfiguration(InputStreamReader(input, StandardCharsets.UTF_8))
        }
        var added = 0
        for (key in bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || yaml.contains(key)) continue
            yaml[key] = bundled.get(key)
            added++
        }
        if (added > 0) {
            plugin.log().info("Added $added missing language keys to $resourcePath.")
        }
    }

    private companion object {
        const val MESSAGES_SECTION = "messages"
    }
}
