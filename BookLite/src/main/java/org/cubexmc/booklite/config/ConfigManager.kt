package org.cubexmc.booklite.config

import org.cubexmc.booklite.BookLitePlugin

class ConfigManager(
    private val plugin: BookLitePlugin,
) {
    private var language: String = "zh_CN"
    private var sqliteFile: String = "books.db"
    private var wal: Boolean = true
    private var cacheMaximumSize: Int = 2048
    private var cacheExpireAfterAccessMillis: Long = 15 * 60_000L
    private var maxPages: Int = 100
    private var maxPageJsonBytes: Int = 8192
    private var maxTotalJsonBytes: Int = 262144
    private var autoConvertSignedBooks: Boolean = true
    private var allowCraftingCopy: Boolean = true
    private var preserveGeneration: Boolean = true
    private var lecternEnabled: Boolean = true
    private var uninstallMode: Boolean = false
    private var passiveOnPlayerJoin: Boolean = true
    private var passiveOnInventoryOpen: Boolean = true
    private var maxItemsPerTick: Int = 64
    private var logConversions: Boolean = true
    private var logRestores: Boolean = true
    private var logAdminReads: Boolean = true

    fun load() {
        plugin.saveDefaultConfig()
        val cfg = plugin.config

        language = cfg.getString("language", "zh_CN") ?: "zh_CN"
        sqliteFile = cfg.getString("storage.sqlite_file", "books.db") ?: "books.db"
        wal = cfg.getBoolean("storage.wal", true)
        cacheMaximumSize = maxOf(1, cfg.getInt("cache.maximum_size", 2048))
        cacheExpireAfterAccessMillis = maxOf(1, cfg.getInt("cache.expire_after_access_minutes", 15)) * 60_000L
        maxPages = maxOf(1, cfg.getInt("limits.max_pages", 100))
        maxPageJsonBytes = maxOf(1, cfg.getInt("limits.max_page_json_bytes", 8192))
        maxTotalJsonBytes = maxOf(1, cfg.getInt("limits.max_total_json_bytes", 262144))

        autoConvertSignedBooks = cfg.getBoolean("behavior.auto_convert_signed_books", true)
        allowCraftingCopy = cfg.getBoolean("behavior.allow_crafting_copy", true)
        preserveGeneration = cfg.getBoolean("behavior.preserve_generation", true)

        lecternEnabled = cfg.getBoolean("lectern.enabled", true)
        uninstallMode = cfg.getBoolean("uninstall.mode", false)
        passiveOnPlayerJoin = cfg.getBoolean("uninstall.passive_on_player_join", true)
        passiveOnInventoryOpen = cfg.getBoolean("uninstall.passive_on_inventory_open", true)
        maxItemsPerTick = maxOf(1, cfg.getInt("uninstall.max_items_per_tick", 64))

        logConversions = cfg.getBoolean("logging.log_conversions", true)
        logRestores = cfg.getBoolean("logging.log_restores", true)
        logAdminReads = cfg.getBoolean("logging.log_admin_reads", true)
    }

    fun getLanguage(): String = language

    fun getSqliteFile(): String = sqliteFile

    fun isWal(): Boolean = wal

    fun getCacheMaximumSize(): Int = cacheMaximumSize

    fun getCacheExpireAfterAccessMillis(): Long = cacheExpireAfterAccessMillis

    fun getMaxPages(): Int = maxPages

    fun getMaxPageJsonBytes(): Int = maxPageJsonBytes

    fun getMaxTotalJsonBytes(): Int = maxTotalJsonBytes

    fun isAutoConvertSignedBooks(): Boolean = autoConvertSignedBooks

    fun isAllowCraftingCopy(): Boolean = allowCraftingCopy

    fun isPreserveGeneration(): Boolean = preserveGeneration

    fun isLecternEnabled(): Boolean = lecternEnabled

    fun isUninstallMode(): Boolean = uninstallMode

    fun isPassiveOnPlayerJoin(): Boolean = passiveOnPlayerJoin

    fun isPassiveOnInventoryOpen(): Boolean = passiveOnInventoryOpen

    fun getMaxItemsPerTick(): Int = maxItemsPerTick

    fun isLogConversions(): Boolean = logConversions

    fun isLogRestores(): Boolean = logRestores

    fun isLogAdminReads(): Boolean = logAdminReads
}
