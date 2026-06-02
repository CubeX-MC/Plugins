package org.cubexmc.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MigrationReport {

    private final String planName;
    private final String resourcePath;
    private final int fromVersion;
    private final int toVersion;
    private final boolean migrated;
    private final boolean skipped;
    private final boolean failed;
    private final File backupFile;
    private final List<String> warnings;
    private final List<String> failures;

    MigrationReport(String planName, String resourcePath, int fromVersion, int toVersion,
                    boolean migrated, boolean skipped, boolean failed, File backupFile,
                    List<String> warnings, List<String> failures) {
        this.planName = planName;
        this.resourcePath = resourcePath;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.migrated = migrated;
        this.skipped = skipped;
        this.failed = failed;
        this.backupFile = backupFile;
        this.warnings = new ArrayList<>(warnings);
        this.failures = new ArrayList<>(failures);
    }

    public String planName() {
        return planName;
    }

    public String resourcePath() {
        return resourcePath;
    }

    public int fromVersion() {
        return fromVersion;
    }

    public int toVersion() {
        return toVersion;
    }

    public boolean migrated() {
        return migrated;
    }

    public boolean skipped() {
        return skipped;
    }

    public boolean failed() {
        return failed;
    }

    public File backupFile() {
        return backupFile;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> failures() {
        return Collections.unmodifiableList(failures);
    }
}
