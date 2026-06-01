package org.cubexmc.booklite.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.model.BookRecord;

public class BookRestorer {

    private final BookLitePlugin plugin;
    private final BookService books;
    private final BookCodec codec;

    public BookRestorer(BookLitePlugin plugin, BookService books, BookCodec codec) {
        this.plugin = plugin;
        this.books = books;
        this.codec = codec;
    }

    public int restoreInventoryNow(Inventory inventory, int limit) {
        if (inventory == null) return 0;
        int restored = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && restored < limit; i++) {
            ItemStack item = contents[i];
            if (!codec.isBookLite(item)) continue;
            try {
                BookRecord record = books.find(codec.readBookId(item));
                if (record == null) continue;
                inventory.setItem(i, codec.createFullBook(record,
                        codec.readGeneration(item), item.getAmount()));
                restored++;
            } catch (SQLException ex) {
                books.logStorageFailure("restore inventory", ex);
                break;
            }
        }
        return restored;
    }

    public void restoreInventoryAsync(Inventory inventory, int limit) {
        if (inventory == null) return;
        List<Candidate> candidates = snapshotCandidates(inventory, limit);
        if (candidates.isEmpty()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Replacement> replacements = new ArrayList<>();
            for (Candidate candidate : candidates) {
                try {
                    BookRecord record = books.find(candidate.bookId());
                    if (record == null) continue;
                    replacements.add(new Replacement(candidate, record));
                } catch (SQLException ex) {
                    books.logStorageFailure("async restore inventory", ex);
                    return;
                }
            }
            if (replacements.isEmpty()) return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Replacement replacement : replacements) {
                    Candidate candidate = replacement.candidate();
                    ItemStack current = inventory.getItem(candidate.slot());
                    if (!codec.isBookLite(current)) continue;
                    String currentId = codec.readBookId(current);
                    if (!candidate.bookId().equals(currentId)) continue;
                    inventory.setItem(candidate.slot(), codec.createFullBook(
                            replacement.record(), candidate.generation(), candidate.amount()));
                }
            });
        });
    }

    private List<Candidate> snapshotCandidates(Inventory inventory, int limit) {
        List<Candidate> out = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && out.size() < limit; i++) {
            ItemStack item = contents[i];
            if (!codec.isBookLite(item)) continue;
            String bookId = codec.readBookId(item);
            if (bookId == null || bookId.isBlank()) continue;
            out.add(new Candidate(i, bookId, codec.readGeneration(item), item.getAmount()));
        }
        return out;
    }

    private record Candidate(int slot, String bookId, int generation, int amount) {}

    private record Replacement(Candidate candidate, BookRecord record) {}
}
