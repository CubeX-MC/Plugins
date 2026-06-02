package org.cubexmc.config;

public final class DefaultMergeOptions {

    private boolean backupBeforeSave;
    private boolean saveWhenChanged = true;
    private boolean includeSections;
    private boolean warnAboutCommentLoss = true;

    private DefaultMergeOptions() {
    }

    public static DefaultMergeOptions copyMissingKeys() {
        return new DefaultMergeOptions();
    }

    public DefaultMergeOptions backupBeforeSave(boolean enabled) {
        this.backupBeforeSave = enabled;
        return this;
    }

    public DefaultMergeOptions saveWhenChanged(boolean enabled) {
        this.saveWhenChanged = enabled;
        return this;
    }

    public DefaultMergeOptions includeSections(boolean enabled) {
        this.includeSections = enabled;
        return this;
    }

    public DefaultMergeOptions warnAboutCommentLoss(boolean enabled) {
        this.warnAboutCommentLoss = enabled;
        return this;
    }

    boolean isBackupBeforeSave() {
        return backupBeforeSave;
    }

    boolean isSaveWhenChanged() {
        return saveWhenChanged;
    }

    boolean isIncludeSections() {
        return includeSections;
    }

    boolean isWarnAboutCommentLoss() {
        return warnAboutCommentLoss;
    }
}
