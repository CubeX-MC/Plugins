package org.cubexmc.config;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class DefaultMergeResult {

    private final boolean changed;
    private final List<String> addedKeys;
    private final File backupFile;

    DefaultMergeResult(boolean changed, List<String> addedKeys, File backupFile) {
        this.changed = changed;
        this.addedKeys = Collections.unmodifiableList(addedKeys);
        this.backupFile = backupFile;
    }

    public boolean changed() {
        return changed;
    }

    public List<String> addedKeys() {
        return addedKeys;
    }

    public File backupFile() {
        return backupFile;
    }
}
