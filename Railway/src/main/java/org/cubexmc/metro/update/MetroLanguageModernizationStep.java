package org.cubexmc.metro.update;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.config.MigrationContext;
import org.cubexmc.config.MigrationStep;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.util.MetroTextRenderer;

final class MetroLanguageModernizationStep implements MigrationStep {

    private final Metro plugin;

    MetroLanguageModernizationStep(Metro plugin) {
        this.plugin = plugin;
    }

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public int toVersion() {
        return MetroMigrations.LANG_VERSION;
    }

    @Override
    public String description() {
        return "Convert Railway language text to MiniMessage and merge v2 defaults.";
    }

    @Override
    public void migrate(MigrationContext context) {
        convertSection(context.yaml());
        mergeCurrentDefaults(context);
    }

    private void convertSection(ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                convertSection(section.getConfigurationSection(key));
            } else if (section.isString(key)) {
                section.set(key, MetroTextRenderer.convertLegacyTemplate(section.getString(key, "")));
            } else if (section.isList(key)) {
                List<?> values = section.getList(key);
                if (values != null && values.stream().allMatch(value -> value instanceof String)) {
                    section.set(key, values.stream()
                            .map(value -> MetroTextRenderer.convertLegacyTemplate((String) value))
                            .toList());
                }
            }
        }
    }

    private void mergeCurrentDefaults(MigrationContext context) {
        try (InputStream inputStream = plugin.getResource(context.resourcePath())) {
            if (inputStream == null) {
                context.fail(context.resourcePath(), "Bundled language resource is missing.");
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key) && !context.yaml().contains(key)) {
                    context.yaml().set(key, defaults.get(key));
                }
            }
        } catch (Exception ex) {
            context.fail(context.resourcePath(), "Failed to merge language defaults: " + ex.getMessage());
        }
    }
}
