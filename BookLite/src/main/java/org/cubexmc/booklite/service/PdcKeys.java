package org.cubexmc.booklite.service;

import org.bukkit.NamespacedKey;
import org.cubexmc.booklite.BookLitePlugin;

public class PdcKeys {

    public static final int SCHEMA_VERSION = 1;

    private final NamespacedKey bookId;
    private final NamespacedKey generation;
    private final NamespacedKey version;

    public PdcKeys(BookLitePlugin plugin) {
        this.bookId = new NamespacedKey(plugin, "book_id");
        this.generation = new NamespacedKey(plugin, "generation");
        this.version = new NamespacedKey(plugin, "version");
    }

    public NamespacedKey bookId() { return bookId; }
    public NamespacedKey generation() { return generation; }
    public NamespacedKey version() { return version; }
}
