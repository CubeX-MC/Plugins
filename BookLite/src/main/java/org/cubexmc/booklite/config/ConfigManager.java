package org.cubexmc.booklite.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.cubexmc.booklite.BookLitePlugin;

public class ConfigManager {

    private final BookLitePlugin plugin;

    private String language;
    private String sqliteFile;
    private boolean wal;
    private int cacheMaximumSize;
    private long cacheExpireAfterAccessMillis;
    private int maxPages;
    private int maxPageJsonBytes;
    private int maxTotalJsonBytes;
    private boolean autoConvertSignedBooks;
    private boolean allowCraftingCopy;
    private boolean preserveGeneration;
    private boolean lecternEnabled;
    private boolean uninstallMode;
    private boolean passiveOnPlayerJoin;
    private boolean passiveOnInventoryOpen;
    private int maxItemsPerTick;
    private boolean logConversions;
    private boolean logRestores;
    private boolean logAdminReads;

    public ConfigManager(BookLitePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        FileConfiguration cfg = plugin.getConfig();

        language = cfg.getString("language", "zh_CN");
        sqliteFile = cfg.getString("storage.sqlite_file", "books.db");
        wal = cfg.getBoolean("storage.wal", true);
        cacheMaximumSize = Math.max(1, cfg.getInt("cache.maximum_size", 2048));
        cacheExpireAfterAccessMillis = Math.max(1, cfg.getInt("cache.expire_after_access_minutes", 15)) * 60_000L;
        maxPages = Math.max(1, cfg.getInt("limits.max_pages", 100));
        maxPageJsonBytes = Math.max(1, cfg.getInt("limits.max_page_json_bytes", 8192));
        maxTotalJsonBytes = Math.max(1, cfg.getInt("limits.max_total_json_bytes", 262144));

        autoConvertSignedBooks = cfg.getBoolean("behavior.auto_convert_signed_books", true);
        allowCraftingCopy = cfg.getBoolean("behavior.allow_crafting_copy", true);
        preserveGeneration = cfg.getBoolean("behavior.preserve_generation", true);

        lecternEnabled = cfg.getBoolean("lectern.enabled", true);
        uninstallMode = cfg.getBoolean("uninstall.mode", false);
        passiveOnPlayerJoin = cfg.getBoolean("uninstall.passive_on_player_join", true);
        passiveOnInventoryOpen = cfg.getBoolean("uninstall.passive_on_inventory_open", true);
        maxItemsPerTick = Math.max(1, cfg.getInt("uninstall.max_items_per_tick", 64));

        logConversions = cfg.getBoolean("logging.log_conversions", true);
        logRestores = cfg.getBoolean("logging.log_restores", true);
        logAdminReads = cfg.getBoolean("logging.log_admin_reads", true);
    }

    public String getLanguage() { return language; }
    public String getSqliteFile() { return sqliteFile; }
    public boolean isWal() { return wal; }
    public int getCacheMaximumSize() { return cacheMaximumSize; }
    public long getCacheExpireAfterAccessMillis() { return cacheExpireAfterAccessMillis; }
    public int getMaxPages() { return maxPages; }
    public int getMaxPageJsonBytes() { return maxPageJsonBytes; }
    public int getMaxTotalJsonBytes() { return maxTotalJsonBytes; }
    public boolean isAutoConvertSignedBooks() { return autoConvertSignedBooks; }
    public boolean isAllowCraftingCopy() { return allowCraftingCopy; }
    public boolean isPreserveGeneration() { return preserveGeneration; }
    public boolean isLecternEnabled() { return lecternEnabled; }
    public boolean isUninstallMode() { return uninstallMode; }
    public boolean isPassiveOnPlayerJoin() { return passiveOnPlayerJoin; }
    public boolean isPassiveOnInventoryOpen() { return passiveOnInventoryOpen; }
    public int getMaxItemsPerTick() { return maxItemsPerTick; }
    public boolean isLogConversions() { return logConversions; }
    public boolean isLogRestores() { return logRestores; }
    public boolean isLogAdminReads() { return logAdminReads; }
}
