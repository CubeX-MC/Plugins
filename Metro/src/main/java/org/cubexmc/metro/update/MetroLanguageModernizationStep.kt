package org.cubexmc.metro.update

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep
import org.cubexmc.metro.Metro
import org.cubexmc.metro.util.MetroTextRenderer

class MetroLanguageModernizationStep(private val plugin: Metro) : MigrationStep {
    override fun fromVersion(): Int = 1

    override fun toVersion(): Int = MetroMigrations.LANG_VERSION

    override fun description(): String = "Convert Metro language text to MiniMessage and merge v2 defaults."

    override fun migrate(context: MigrationContext) {
        convertSection(context.yaml())
        mergeCurrentDefaults(context)
    }

    private fun convertSection(section: ConfigurationSection?) {
        if (section == null) {
            return
        }
        for (key in section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                convertSection(section.getConfigurationSection(key))
            } else if (section.isString(key)) {
                section.set(key, MetroTextRenderer.convertLegacyTemplate(section.getString(key, "")))
            } else if (section.isList(key)) {
                val values = section.getList(key)
                if (values != null && values.all { value -> value is String }) {
                    section.set(
                        key,
                        values.map { value -> MetroTextRenderer.convertLegacyTemplate(value as String) },
                    )
                }
            }
        }
    }

    private fun mergeCurrentDefaults(context: MigrationContext) {
        try {
            plugin.getResource(context.resourcePath()).use { inputStream ->
                if (inputStream == null) {
                    context.fail(context.resourcePath(), "Bundled language resource is missing.")
                    return
                }
                val defaults = YamlConfiguration.loadConfiguration(
                    InputStreamReader(inputStream, StandardCharsets.UTF_8),
                )
                for (key in defaults.getKeys(true)) {
                    if (!defaults.isConfigurationSection(key) && !context.yaml().contains(key)) {
                        context.yaml().set(key, defaults.get(key))
                    }
                }
            }
        } catch (ex: Exception) {
            context.fail(context.resourcePath(), "Failed to merge language defaults: ${ex.message}")
        }
    }
}
