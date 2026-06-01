package org.cubexmc.booklite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.inventory.meta.BookMeta;
import org.cubexmc.booklite.config.ConfigManager;
import org.cubexmc.booklite.model.BookRecord;
import org.junit.jupiter.api.Test;
import net.md_5.bungee.api.chat.BaseComponent;

/**
 * Covers the BookCodec logic that does not require a live Bukkit server:
 * content hashing, generation conversion/limits, and validation. ItemStack
 * assembly (createShell/createReadable) needs a running server and is left to
 * the manual regression matrix.
 */
class BookCodecTest {

    private BookMeta meta(String title, String author, List<String> pages) {
        BookMeta meta = mock(BookMeta.class);
        lenient().when(meta.hasTitle()).thenReturn(title != null);
        lenient().when(meta.getTitle()).thenReturn(title);
        lenient().when(meta.hasAuthor()).thenReturn(author != null);
        lenient().when(meta.getAuthor()).thenReturn(author);
        lenient().when(meta.getPages()).thenReturn(pages);
        return meta;
    }

    @Test
    void identicalContentProducesSameHash() {
        BookCodec codec = new BookCodec(null, null, null);
        BookRecord first = codec.createRecord(meta("T", "A", List.of("p1", "p2")), "fallback");
        BookRecord second = codec.createRecord(meta("T", "A", List.of("p1", "p2")), "other");
        assertEquals(first.contentHash(), second.contentHash());
    }

    @Test
    void differentContentProducesDifferentHash() {
        BookCodec codec = new BookCodec(null, null, null);
        BookRecord base = codec.createRecord(meta("T", "A", List.of("p1")), "fb");
        assertNotEquals(base.contentHash(),
                codec.createRecord(meta("T", "A", List.of("p1", "p2")), "fb").contentHash());
        assertNotEquals(base.contentHash(),
                codec.createRecord(meta("T2", "A", List.of("p1")), "fb").contentHash());
        assertNotEquals(base.contentHash(),
                codec.createRecord(meta("T", "A2", List.of("p1")), "fb").contentHash());
    }

    @Test
    void fallbackAuthorDoesNotAffectHashWhenAuthorPresent() {
        BookCodec codec = new BookCodec(null, null, null);
        BookRecord a = codec.createRecord(meta("T", "A", List.of("p")), "fallbackOne");
        BookRecord b = codec.createRecord(meta("T", "A", List.of("p")), "fallbackTwo");
        assertEquals(a.contentHash(), b.contentHash());
    }

    @Test
    void generationRoundTripsBetweenIntAndEnum() {
        BookCodec codec = new BookCodec(null, null, null);
        for (int g = 0; g <= 3; g++) {
            assertEquals(g, codec.generationToInt(metaWithGeneration(codec.intToGeneration(g))));
        }
    }

    @Test
    void generationIntClampsNegativeAndOverflow() {
        BookCodec codec = new BookCodec(null, null, null);
        assertEquals(BookMeta.Generation.ORIGINAL, codec.intToGeneration(-5));
        assertEquals(BookMeta.Generation.TATTERED, codec.intToGeneration(3));
        assertEquals(BookMeta.Generation.ORIGINAL, codec.intToGeneration(99));
    }

    @Test
    void copyGenerationIsCappedAtCopyOfCopy() {
        BookCodec codec = new BookCodec(null, null, null);
        assertTrue(codec.canCopyGeneration(0));
        assertTrue(codec.canCopyGeneration(1));
        assertFalse(codec.canCopyGeneration(2));
        assertFalse(codec.canCopyGeneration(3));

        assertEquals(1, codec.nextGeneration(0));
        assertEquals(2, codec.nextGeneration(1));
        assertEquals(2, codec.nextGeneration(2));
        assertEquals(2, codec.nextGeneration(5));
        assertEquals(1, codec.nextGeneration(-3));
    }

    @Test
    void validateRejectsTooManyPages() {
        ConfigManager config = config(2, 8192, 262144);
        BookCodec codec = new BookCodec(null, null, config);
        BookCodec.ValidationResult result = codec.validate(
                new BookRecord("id", "hash", "t", "a", List.of("1", "2", "3"), 0, 0, null));
        assertFalse(result.ok());
        assertEquals("book.fail_too_many_pages", result.key());
        assertEquals(3, result.actual());
        assertEquals(2, result.max());
    }

    @Test
    void validateRejectsOversizedPage() {
        ConfigManager config = config(100, 4, 262144);
        BookCodec codec = new BookCodec(null, null, config);
        BookCodec.ValidationResult result = codec.validate(
                new BookRecord("id", "hash", "t", "a", List.of("a long page"), 0, 0, null));
        assertFalse(result.ok());
        assertEquals("book.fail_page_too_large", result.key());
    }

    @Test
    void validateAcceptsBookWithinLimits() {
        ConfigManager config = config(100, 8192, 262144);
        BookCodec codec = new BookCodec(null, null, config);
        BookCodec.ValidationResult result = codec.validate(
                new BookRecord("id", "hash", "t", "a", List.of("p1", "p2"), 0, 0, null));
        assertTrue(result.ok());
    }

    @Test
    void visibleShellIdentityMatchesOriginalBook() {
        BookCodec codec = new BookCodec(null, null, null);
        BookRecord record = new BookRecord("id", "hash", "Original Title", "Original Author",
                List.of("page"), 0, 0, null);

        assertEquals("Original Title", codec.visibleTitle(record));
        assertEquals("Original Author", codec.visibleAuthor(record));
    }

    @Test
    void visibleShellIdentityUsesVanillaLikeFallbacks() {
        BookCodec codec = new BookCodec(null, null, null);
        BookRecord record = new BookRecord("id", "hash", "", "", List.of("page"), 0, 0, null);

        assertEquals("Untitled", codec.visibleTitle(record));
        assertEquals("Unknown", codec.visibleAuthor(record));
    }

    @Test
    void legacyBookIdCandidateReadsOldShellLoreAndTitle() {
        BookCodec codec = new BookCodec(null, null, null);

        assertEquals("abcdef12", codec.legacyBookIdCandidate("Plain title",
                List.of("BookLite ID: ABCDEF12", "Right-click to read stored content.")));
        assertEquals("01234567", codec.legacyBookIdCandidate("BookLite #01234567", List.of()));
    }

    @Test
    void legacyBookIdCandidateRejectsPlainBooks() {
        BookCodec codec = new BookCodec(null, null, null);

        assertNull(codec.legacyBookIdCandidate("BookLite Guide", List.of("Chapter 1")));
    }

    @Test
    void storedPagesParseSerializedComponentsAndLegacyText() {
        BookCodec codec = new BookCodec(null, null, null);

        BaseComponent[] json = codec.toComponents("{\"text\":\"Hello\"}");
        BaseComponent[] legacy = codec.toComponents("Hello");

        assertEquals("Hello", BaseComponent.toPlainText(json));
        assertEquals("Hello", BaseComponent.toPlainText(legacy));
    }

    @Test
    void serializedComponentPageDetectionIsShapeBased() {
        BookCodec codec = new BookCodec(null, null, null);

        assertTrue(codec.looksLikeSerializedComponentPage("{\"text\":\"Hi\"}"));
        assertTrue(codec.looksLikeSerializedComponentPage("[{\"text\":\"Hi\"}]"));
        assertFalse(codec.looksLikeSerializedComponentPage("Plain page"));
    }

    private BookMeta metaWithGeneration(BookMeta.Generation generation) {
        BookMeta meta = mock(BookMeta.class);
        when(meta.getGeneration()).thenReturn(generation);
        return meta;
    }

    private ConfigManager config(int maxPages, int maxPageBytes, int maxTotalBytes) {
        ConfigManager config = mock(ConfigManager.class);
        lenient().when(config.getMaxPages()).thenReturn(maxPages);
        lenient().when(config.getMaxPageJsonBytes()).thenReturn(maxPageBytes);
        lenient().when(config.getMaxTotalJsonBytes()).thenReturn(maxTotalBytes);
        return config;
    }
}
