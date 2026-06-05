package org.cubexmc.metro.update;

import java.util.List;
import org.cubexmc.config.MigrationException;
import org.cubexmc.config.MigrationFailurePolicy;
import org.cubexmc.config.MigrationPlan;
import org.cubexmc.config.MigrationRunner;
import org.cubexmc.config.ResourceFiles;
import org.cubexmc.metro.Metro;

public final class MetroMigrations {

    public static final int CONFIG_VERSION = 2;
    public static final int LANG_VERSION = 2;
    public static final List<String> BUNDLED_LANGUAGES =
            List.of("zh_CN", "zh_TW", "en_US", "de_DE", "es_ES", "nl_NL", "tr_TR");

    private MetroMigrations() {
    }

    public static void ensureConfigResources(Metro plugin) {
        ResourceFiles resources = new ResourceFiles(plugin);
        resources.saveIfMissing("config.yml");
        resources.saveIfMissing("entity.yml");
        resources.saveIfMissing("lines.yml");
        resources.saveIfMissing("stops.yml");
    }

    public static void migrateConfig(Metro plugin) throws MigrationException {
        new MigrationRunner(plugin).run(MigrationPlan.yaml("Railway config", "config.yml")
                .versionKey("config-version")
                .missingVersion(1)
                .targetVersion(CONFIG_VERSION)
                .failurePolicy(MigrationFailurePolicy.ABORT)
                .addStep(new MetroConfigModernizationStep(plugin)));
    }

    public static void ensureEntityDefaults(Metro plugin) {
        ConfigUpdater.applyDefaultsToFile(plugin, "entity.yml");
    }

    public static void ensureLanguageResources(Metro plugin) {
        ResourceFiles resources = new ResourceFiles(plugin);
        for (String language : BUNDLED_LANGUAGES) {
            resources.saveIfMissing(languagePath(language));
        }
    }

    public static void migrateBundledLanguages(Metro plugin) throws MigrationException {
        for (String language : BUNDLED_LANGUAGES) {
            migrateLanguage(plugin, language);
        }
    }

    public static void migrateLanguage(Metro plugin, String language) throws MigrationException {
        new MigrationRunner(plugin).run(MigrationPlan.yaml("Railway language " + language, languagePath(language))
                .versionKey("lang-version")
                .missingVersion(1)
                .targetVersion(LANG_VERSION)
                .failurePolicy(MigrationFailurePolicy.ABORT)
                .addStep(new MetroLanguageModernizationStep(plugin)));
    }

    static String languagePath(String language) {
        return "lang/" + language + ".yml";
    }
}
