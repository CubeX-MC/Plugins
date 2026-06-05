package org.cubexmc.booklite

import org.cubexmc.booklite.command.BookLiteCommand
import org.cubexmc.booklite.config.ConfigManager
import org.cubexmc.booklite.lang.LanguageManager
import org.cubexmc.booklite.listener.BookListener
import org.cubexmc.booklite.service.BookCache
import org.cubexmc.booklite.service.BookCodec
import org.cubexmc.booklite.service.BookRestorer
import org.cubexmc.booklite.service.BookService
import org.cubexmc.booklite.service.PdcKeys
import org.cubexmc.booklite.storage.BookRepository
import org.cubexmc.config.LegacyTextToMiniMessageStep
import org.cubexmc.config.MigrationException
import org.cubexmc.config.MigrationPlan
import org.cubexmc.config.MigrationRunner
import org.cubexmc.config.NoOpMigrationStep
import org.cubexmc.config.ResourceFiles
import org.cubexmc.core.CubexPlugin

class BookLitePlugin : CubexPlugin() {
    private lateinit var configManager: ConfigManager
    private lateinit var languageManager: LanguageManager
    private lateinit var pdcKeys: PdcKeys
    private lateinit var repository: BookRepository
    private lateinit var cache: BookCache
    private lateinit var codec: BookCodec
    private lateinit var bookService: BookService
    private lateinit var bookRestorer: BookRestorer
    private lateinit var resourceFiles: ResourceFiles

    override fun enablePlugin() {
        resourceFiles = ResourceFiles(this)
        saveDefaultResources()
        try {
            migrateConfigAndLang()
        } catch (exception: MigrationException) {
            logger.severe("BookLite enable aborted: migration failed. ${exception.message}")
            abortEnable("BookLite migration failed. See logs for details.")
        }

        configManager = ConfigManager(this)
        configManager.load()

        languageManager = LanguageManager(this, configManager.getLanguage())
        languageManager.load()

        pdcKeys = PdcKeys(this)
        repository = BookRepository(this, configManager)
        repository.init()
        val closeRepository = Runnable { repository.close() }
        bind(closeRepository)

        cache = BookCache(configManager.getCacheMaximumSize(), configManager.getCacheExpireAfterAccessMillis())
        codec = BookCodec(this, pdcKeys, configManager)
        bookService = BookService(this, repository, cache)
        bookRestorer = BookRestorer(this, bookService, codec)

        server.pluginManager.registerEvents(
            BookListener(this, bookService, codec, bookRestorer, languageManager),
            this,
        )

        val root = getCommand("booklite")
        if (root != null) {
            val executor = BookLiteCommand(this, bookService, codec, bookRestorer, languageManager)
            root.setExecutor(executor)
            root.tabCompleter = executor
        }

        Metrics(this, 31451)

        logger.info("BookLite ${description.version} enabled.")
    }

    override fun disablePlugin() {
    }

    fun reloadAll() {
        val oldSqliteFile = configManager.getSqliteFile()
        val oldWal = configManager.isWal()
        saveDefaultResources()
        try {
            migrateConfigAndLang()
        } catch (exception: MigrationException) {
            logger.severe("BookLite reload aborted: migration failed. ${exception.message}")
            return
        }
        reloadConfig()
        configManager.load()
        languageManager.setLocale(configManager.getLanguage())
        languageManager.load()
        if (oldSqliteFile != configManager.getSqliteFile() || oldWal != configManager.isWal()) {
            repository.close()
            repository.init()
            cache.clear()
        }
        cache.resize(configManager.getCacheMaximumSize(), configManager.getCacheExpireAfterAccessMillis())
    }

    private fun saveDefaultResources() {
        resourceFiles.saveIfMissing(listOf("config.yml", "lang/zh_CN.yml", "lang/en_US.yml"))
    }

    @Throws(MigrationException::class)
    private fun migrateConfigAndLang() {
        val migrations = MigrationRunner(this)
        migrations.run(
            MigrationPlan.yaml("BookLite config", "config.yml")
                .versionKey("config-version")
                .targetVersion(2)
                .addStep(NoOpMigrationStep(1, 2, "Add BookLite config-version.")),
        )
        migrateLang(migrations, "zh_CN")
        migrateLang(migrations, "en_US")
    }

    @Throws(MigrationException::class)
    private fun migrateLang(migrations: MigrationRunner, locale: String) {
        migrations.run(
            MigrationPlan.yaml("BookLite lang $locale", "lang/$locale.yml")
                .versionKey("lang-version")
                .targetVersion(2)
                .addStep(LegacyTextToMiniMessageStep(1, 2)),
        )
    }

    fun configManager(): ConfigManager = configManager

    fun languageManager(): LanguageManager = languageManager

    fun pdcKeys(): PdcKeys = pdcKeys

    fun repository(): BookRepository = repository

    fun cache(): BookCache = cache

    fun codec(): BookCodec = codec

    fun bookService(): BookService = bookService

    fun bookRestorer(): BookRestorer = bookRestorer
}
