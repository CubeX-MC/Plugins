package org.cubexmc.metro.update;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.config.MigrationContext;
import org.cubexmc.config.MigrationStep;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.util.MetroTextRenderer;

final class MetroConfigModernizationStep implements MigrationStep {

    static final List<String> DISPLAY_PATHS = List.of(
            "titles.enter_stop.title",
            "titles.enter_stop.subtitle",
            "titles.enter_stop.actionbar",
            "titles.enter_stop.start_stop.title",
            "titles.enter_stop.start_stop.subtitle",
            "titles.enter_stop.start_stop.actionbar",
            "titles.enter_stop.end_stop.title",
            "titles.enter_stop.end_stop.subtitle",
            "titles.enter_stop.end_stop.actionbar",
            "titles.stop_continuous.title",
            "titles.stop_continuous.subtitle",
            "titles.stop_continuous.actionbar",
            "titles.stop_continuous.start_stop.title",
            "titles.stop_continuous.start_stop.subtitle",
            "titles.stop_continuous.start_stop.actionbar",
            "titles.stop_continuous.end_stop.title",
            "titles.stop_continuous.end_stop.subtitle",
            "titles.stop_continuous.end_stop.actionbar",
            "titles.arrive_stop.title",
            "titles.arrive_stop.subtitle",
            "titles.terminal_stop.title",
            "titles.terminal_stop.subtitle",
            "titles.departure.title",
            "titles.departure.subtitle",
            "titles.departure.actionbar",
            "titles.waiting.title",
            "titles.waiting.subtitle",
            "titles.waiting.actionbar",
            "scoreboard.styles.current_stop",
            "scoreboard.styles.passed_stop",
            "scoreboard.styles.waiting_next_stop",
            "scoreboard.styles.moving_next_stop",
            "scoreboard.styles.terminal_stop",
            "scoreboard.styles.next_stop",
            "scoreboard.styles.other_stops",
            "scoreboard.styles.folding_symbol");

    private final Metro plugin;

    MetroConfigModernizationStep(Metro plugin) {
        this.plugin = plugin;
    }

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public int toVersion() {
        return MetroMigrations.CONFIG_VERSION;
    }

    @Override
    public String description() {
        return "Convert Railway config display templates to MiniMessage and merge v2 defaults.";
    }

    @Override
    public void migrate(MigrationContext context) {
        ConfigUpdater.migrateLegacyEnterStop(context.yaml());
        for (String path : DISPLAY_PATHS) {
            if (context.yaml().isString(path)) {
                context.yaml().set(path, MetroTextRenderer.convertLegacyTemplate(context.yaml().getString(path, "")));
            }
        }
        mergeCurrentDefaults(context);
    }

    private void mergeCurrentDefaults(MigrationContext context) {
        try (InputStream inputStream = plugin.getResource(context.resourcePath())) {
            if (inputStream == null) {
                context.fail(context.resourcePath(), "Bundled config resource is missing.");
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
            context.fail(context.resourcePath(), "Failed to merge config defaults: " + ex.getMessage());
        }
    }
}
