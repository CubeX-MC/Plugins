package org.cubexmc.booklite;

import org.bukkit.command.PluginCommand;
import org.cubexmc.booklite.command.BookLiteCommand;
import org.cubexmc.booklite.config.ConfigManager;
import org.cubexmc.booklite.lang.LanguageManager;
import org.cubexmc.booklite.listener.BookListener;
import org.cubexmc.booklite.service.BookCache;
import org.cubexmc.booklite.service.BookCodec;
import org.cubexmc.booklite.service.BookRestorer;
import org.cubexmc.booklite.service.BookService;
import org.cubexmc.booklite.service.PdcKeys;
import org.cubexmc.booklite.storage.BookRepository;
import org.cubexmc.core.CubexPlugin;

public class BookLitePlugin extends CubexPlugin {

    private ConfigManager configManager;
    private LanguageManager languageManager;
    private PdcKeys pdcKeys;
    private BookRepository repository;
    private BookCache cache;
    private BookCodec codec;
    private BookService bookService;
    private BookRestorer bookRestorer;

    @Override
    protected void enablePlugin() {
        saveDefaultResources();

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.languageManager = new LanguageManager(this, configManager.getLanguage());
        this.languageManager.load();

        this.pdcKeys = new PdcKeys(this);
        this.repository = new BookRepository(this, configManager);
        this.repository.init();
        Runnable closeRepository = repository::close;
        bind(closeRepository);

        this.cache = new BookCache(configManager.getCacheMaximumSize(),
                configManager.getCacheExpireAfterAccessMillis());
        this.codec = new BookCodec(this, pdcKeys, configManager);
        this.bookService = new BookService(this, repository, cache);
        this.bookRestorer = new BookRestorer(this, bookService, codec);

        getServer().getPluginManager().registerEvents(
                new BookListener(this, bookService, codec, bookRestorer, languageManager), this);

        PluginCommand root = getCommand("booklite");
        if (root != null) {
            BookLiteCommand executor = new BookLiteCommand(this, bookService, codec,
                    bookRestorer, languageManager);
            root.setExecutor(executor);
            root.setTabCompleter(executor);
        }

        new Metrics(this, 31451);

        getLogger().info("BookLite " + getDescription().getVersion() + " enabled.");
    }

    @Override
    protected void disablePlugin() {
    }

    public void reloadAll() {
        String oldSqliteFile = configManager.getSqliteFile();
        boolean oldWal = configManager.isWal();
        saveDefaultResources();
        reloadConfig();
        configManager.load();
        languageManager.setLocale(configManager.getLanguage());
        languageManager.load();
        if (!oldSqliteFile.equals(configManager.getSqliteFile()) || oldWal != configManager.isWal()) {
            repository.close();
            repository.init();
            cache.clear();
        }
        cache.resize(configManager.getCacheMaximumSize(), configManager.getCacheExpireAfterAccessMillis());
    }

    private void saveDefaultResources() {
        saveResourcesIfMissing("config.yml", "lang/zh_CN.yml", "lang/en_US.yml");
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public LanguageManager languageManager() {
        return languageManager;
    }

    public PdcKeys pdcKeys() {
        return pdcKeys;
    }

    public BookRepository repository() {
        return repository;
    }

    public BookCache cache() {
        return cache;
    }

    public BookCodec codec() {
        return codec;
    }

    public BookService bookService() {
        return bookService;
    }

    public BookRestorer bookRestorer() {
        return bookRestorer;
    }
}
