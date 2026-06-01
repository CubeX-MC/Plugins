package org.cubexmc.booklite.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BookRecordTest {

    @Test
    void generatesIdWhenNullAndKeepsExplicitId() {
        BookRecord generated = new BookRecord(null, "hash", "t", "a",
                List.of("p"), 0, 0);
        assertNotNull(generated.id());
        assertFalse(generated.id().isBlank());

        BookRecord explicit = new BookRecord("id-1", "hash", "t", "a",
                List.of("p"), 1_000, 1_000);
        assertEquals("id-1", explicit.id());
    }

    @Test
    void shortIdTruncatesToEightChars() {
        BookRecord longId = new BookRecord("0123456789abcdef", "hash", "t", "a",
                List.of("p"), 0, 0);
        assertEquals("01234567", longId.shortId());

        BookRecord shortId = new BookRecord("abc", "hash", "t", "a",
                List.of("p"), 0, 0);
        assertEquals("abc", shortId.shortId());
    }

    @Test
    void pagesAreDefensivelyCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        BookRecord rec = new BookRecord("id", "hash", "t", "a", source, 0, 0);

        source.add("c");
        assertEquals(2, rec.totalPages());
        assertThrows(UnsupportedOperationException.class, () -> rec.pages().add("x"));
    }

    @Test
    void nullPagesFallBackToSingleEmptyPage() {
        BookRecord rec = new BookRecord("id", "hash", "t", "a", null, 0, 0);
        assertEquals(1, rec.totalPages());
        assertEquals("", rec.pages().get(0));
    }

    @Test
    void lastAccessedAtIsMutable() {
        BookRecord rec = new BookRecord("id", "hash", "t", "a",
                List.of("p"), 0, 0, 5_000L);
        assertEquals(5_000L, rec.lastAccessedAt());

        rec.setLastAccessedAt(6_000L);
        assertEquals(6_000L, rec.lastAccessedAt());
    }
}
