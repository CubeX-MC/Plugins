package org.cubexmc.booklite.service

import org.bukkit.NamespacedKey
import org.cubexmc.booklite.BookLitePlugin

class PdcKeys(plugin: BookLitePlugin) {
    private val bookId = NamespacedKey(plugin, "book_id")
    private val generation = NamespacedKey(plugin, "generation")
    private val version = NamespacedKey(plugin, "version")

    fun bookId(): NamespacedKey = bookId

    fun generation(): NamespacedKey = generation

    fun version(): NamespacedKey = version

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}
