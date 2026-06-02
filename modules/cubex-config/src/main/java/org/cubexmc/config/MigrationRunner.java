package org.cubexmc.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.core.CubexPlugin;

public final class MigrationRunner {

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CubexPlugin plugin;

    public MigrationRunner(CubexPlugin plugin) {
        this.plugin = plugin;
    }

    public MigrationReport run(MigrationPlan plan) throws MigrationException {
        if (plan == null) {
            throw new MigrationException("Migration plan is null.");
        }
        File file = new File(plugin.getDataFolder(), plan.resourcePath());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int currentVersion = yaml.isSet(plan.versionKey())
                ? yaml.getInt(plan.versionKey(), plan.missingVersion())
                : plan.missingVersion();
        List<String> warnings = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        if (currentVersion > plan.targetVersion()) {
            plugin.getLogger().warning("Skipping " + plan.resourcePath()
                    + " migration: file version " + currentVersion
                    + " is newer than target " + plan.targetVersion() + ".");
            return new MigrationReport(plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                    false, true, false, null, warnings, failures);
        }
        if (currentVersion == plan.targetVersion()) {
            return new MigrationReport(plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                    false, true, false, null, warnings, failures);
        }

        File backupFile = null;
        try {
            backupFile = backup(file, plan);
            SimpleMigrationContext context = new SimpleMigrationContext(file, plan.resourcePath(), yaml, warnings, failures);
            int version = currentVersion;
            for (MigrationStep step : orderedSteps(plan)) {
                if (step.fromVersion() == version && step.toVersion() <= plan.targetVersion()) {
                    step.migrate(context);
                    version = step.toVersion();
                }
            }
            if (version != plan.targetVersion()) {
                context.fail(plan.versionKey(), "No migration path from " + currentVersion + " to " + plan.targetVersion() + ".");
            }
            if (!failures.isEmpty() && plan.failurePolicy() == MigrationFailurePolicy.ABORT) {
                throw new MigrationException("Migration failed for " + plan.resourcePath() + ": " + failures);
            }
            yaml.set(plan.versionKey(), plan.targetVersion());
            saveAtomically(file, yaml, backupFile, plan.restoreBackupOnSaveFailure());
            logReport(plan, currentVersion, backupFile, warnings);
            return new MigrationReport(plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                    true, false, false, backupFile, warnings, failures);
        } catch (MigrationException ex) {
            logFailure(plan, failures, ex);
            returnOrThrow(plan, ex);
            return new MigrationReport(plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                    false, false, true, backupFile, warnings, failures);
        } catch (Exception ex) {
            MigrationException wrapped = new MigrationException("Migration failed for " + plan.resourcePath(), ex);
            logFailure(plan, failures, wrapped);
            returnOrThrow(plan, wrapped);
            return new MigrationReport(plan.name(), plan.resourcePath(), currentVersion, plan.targetVersion(),
                    false, false, true, backupFile, warnings, failures);
        }
    }

    private List<MigrationStep> orderedSteps(MigrationPlan plan) {
        List<MigrationStep> steps = new ArrayList<>(plan.steps());
        steps.sort(Comparator.comparingInt(MigrationStep::fromVersion)
                .thenComparingInt(MigrationStep::toVersion));
        return steps;
    }

    private File backup(File file, MigrationPlan plan) throws Exception {
        if (!file.exists()) {
            return null;
        }
        Path relative = Path.of(plan.resourcePath());
        Path backupRoot = plugin.getDataFolder().toPath()
                .resolve(plan.backupDirectory())
                .resolve(LocalDateTime.now().format(BACKUP_TIMESTAMP));
        Path backupPath = backupRoot.resolve(relative);
        Files.createDirectories(backupPath.getParent());
        Files.copy(file.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return backupPath.toFile();
    }

    private void saveAtomically(File file, YamlConfiguration yaml, File backupFile, boolean restoreBackup) throws Exception {
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
        try {
            yaml.save(temp.toFile());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.deleteIfExists(temp);
            if (restoreBackup && backupFile != null && backupFile.exists()) {
                Files.copy(backupFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw ex;
        }
    }

    private void logReport(MigrationPlan plan, int currentVersion, File backupFile, List<String> warnings) {
        plugin.getLogger().info("Migrated " + plan.resourcePath() + " from v" + currentVersion
                + " to v" + plan.targetVersion()
                + (backupFile == null ? "." : " (backup: " + backupFile.getPath() + ")."));
        for (String warning : warnings) {
            plugin.getLogger().warning(warning);
        }
    }

    private void logFailure(MigrationPlan plan, List<String> failures, MigrationException ex) {
        for (String failure : failures) {
            plugin.getLogger().severe(failure);
        }
        plugin.getLogger().log(Level.SEVERE, "Failed to migrate " + plan.resourcePath() + ".", ex);
    }

    private void returnOrThrow(MigrationPlan plan, MigrationException ex) throws MigrationException {
        if (plan.failurePolicy() == MigrationFailurePolicy.ABORT) {
            throw ex;
        }
    }

    private static final class SimpleMigrationContext implements MigrationContext {
        private final File file;
        private final String resourcePath;
        private final YamlConfiguration yaml;
        private final List<String> warnings;
        private final List<String> failures;

        private SimpleMigrationContext(File file, String resourcePath, YamlConfiguration yaml,
                                       List<String> warnings, List<String> failures) {
            this.file = file;
            this.resourcePath = resourcePath;
            this.yaml = yaml;
            this.warnings = warnings;
            this.failures = failures;
        }

        @Override
        public File file() {
            return file;
        }

        @Override
        public String resourcePath() {
            return resourcePath;
        }

        @Override
        public YamlConfiguration yaml() {
            return yaml;
        }

        @Override
        public void warning(String path, String message) {
            warnings.add(resourcePath + ":" + path + ": " + message);
        }

        @Override
        public void fail(String path, String message) {
            failures.add(resourcePath + ":" + path + ": " + message);
        }
    }
}
