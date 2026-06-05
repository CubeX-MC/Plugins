package org.cubexmc.booklite.service

import java.sql.SQLException
import org.bukkit.inventory.Inventory
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.booklite.model.BookRecord

class BookRestorer(
    private val plugin: BookLitePlugin,
    private val books: BookService,
    private val codec: BookCodec,
) {
    fun restoreInventoryNow(inventory: Inventory?, limit: Int): Int {
        if (inventory == null) {
            return 0
        }
        var restored = 0
        val contents = inventory.contents
        var index = 0
        while (index < contents.size && restored < limit) {
            val item = contents[index]
            if (codec.isBookLite(item)) {
                try {
                    val record = books.find(codec.readBookId(item))
                    if (record != null) {
                        inventory.setItem(index, codec.createFullBook(record, codec.readGeneration(item), item?.amount ?: 1))
                        restored++
                    }
                } catch (exception: SQLException) {
                    books.logStorageFailure("restore inventory", exception)
                    break
                }
            }
            index++
        }
        return restored
    }

    fun restoreInventoryAsync(inventory: Inventory?, limit: Int) {
        if (inventory == null) {
            return
        }
        val candidates = snapshotCandidates(inventory, limit)
        if (candidates.isEmpty()) {
            return
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val replacements = ArrayList<Replacement>()
            for (candidate in candidates) {
                try {
                    val record = books.find(candidate.bookId) ?: continue
                    replacements.add(Replacement(candidate, record))
                } catch (exception: SQLException) {
                    books.logStorageFailure("async restore inventory", exception)
                    return@Runnable
                }
            }
            if (replacements.isEmpty()) {
                return@Runnable
            }

            plugin.server.scheduler.runTask(plugin, Runnable {
                for (replacement in replacements) {
                    val candidate = replacement.candidate
                    val current = inventory.getItem(candidate.slot)
                    if (!codec.isBookLite(current)) {
                        continue
                    }
                    val currentId = codec.readBookId(current)
                    if (candidate.bookId != currentId) {
                        continue
                    }
                    inventory.setItem(
                        candidate.slot,
                        codec.createFullBook(replacement.record, candidate.generation, candidate.amount),
                    )
                }
            })
        })
    }

    private fun snapshotCandidates(inventory: Inventory, limit: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        val contents = inventory.contents
        var index = 0
        while (index < contents.size && out.size < limit) {
            val item = contents[index]
            if (codec.isBookLite(item)) {
                val bookId = codec.readBookId(item)
                if (!bookId.isNullOrBlank()) {
                    out.add(Candidate(index, bookId, codec.readGeneration(item), item?.amount ?: 1))
                }
            }
            index++
        }
        return out
    }

    private class Candidate(
        val slot: Int,
        val bookId: String,
        val generation: Int,
        val amount: Int,
    )

    private class Replacement(
        val candidate: Candidate,
        val record: BookRecord,
    )
}
