package org.cubexmc.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PaginationTest {

    @Test
    void shouldRoundPartialPagesUp() {
        assertEquals(1, new Pagination(1, 10).pageCount());
        assertEquals(1, new Pagination(10, 10).pageCount());
        assertEquals(2, new Pagination(11, 10).pageCount());
        assertEquals(3, new Pagination(21, 10).pageCount());
    }

    @Test
    void shouldTreatAnEmptyListAsOneEmptyPage() {
        Pagination pagination = new Pagination(0, 10);
        // Callers render "page 1 of 1" rather than "page 1 of 0".
        assertEquals(1, pagination.pageCount());
        assertEquals(0, pagination.countOn(1));
        assertFalse(pagination.hasPrevious(1));
        assertFalse(pagination.hasNext(1));
        assertTrue(pagination.slice(List.of(), 1).isEmpty());
    }

    @Test
    void shouldClampOutOfRangePagesInsteadOfThrowing() {
        Pagination pagination = new Pagination(25, 10);
        assertEquals(1, pagination.clamp(0));
        assertEquals(1, pagination.clamp(-5));
        assertEquals(3, pagination.clamp(3));
        assertEquals(3, pagination.clamp(99));
    }

    @Test
    void shouldReportNeighboursAtTheEdges() {
        Pagination pagination = new Pagination(25, 10);

        assertFalse(pagination.hasPrevious(1));
        assertTrue(pagination.hasNext(1));

        assertTrue(pagination.hasPrevious(2));
        assertTrue(pagination.hasNext(2));

        assertTrue(pagination.hasPrevious(3));
        assertFalse(pagination.hasNext(3));

        // An out-of-range page is clamped first, so it reports the edge page's neighbours.
        assertFalse(pagination.hasNext(99));
    }

    @Test
    void shouldSliceIncludingAShortFinalPage() {
        List<String> items = List.of("a", "b", "c", "d", "e", "f", "g");
        Pagination pagination = Pagination.of(items, 3);

        assertEquals(3, pagination.pageCount());
        assertEquals(List.of("a", "b", "c"), pagination.slice(items, 1));
        assertEquals(List.of("d", "e", "f"), pagination.slice(items, 2));
        assertEquals(List.of("g"), pagination.slice(items, 3));
        assertEquals(1, pagination.countOn(3));

        // Clamped, so an over-large page returns the last page rather than an empty list.
        assertEquals(List.of("g"), pagination.slice(items, 42));
    }

    @Test
    void shouldNotOverrunWhenTheListIsShorterThanTheDeclaredTotal() {
        // Total came from a database count but the fetched page is short (rows deleted meanwhile).
        Pagination pagination = new Pagination(100, 10);
        List<String> fetched = List.of("a", "b");

        assertTrue(pagination.slice(fetched, 5).isEmpty());
        assertEquals(List.of("a", "b"), pagination.slice(fetched, 1));
    }

    @Test
    void shouldExposeIndicesForDatabaseOffsetQueries() {
        Pagination pagination = new Pagination(25, 10);

        assertEquals(0, pagination.firstIndex(1));
        assertEquals(10, pagination.lastIndexExclusive(1));

        assertEquals(20, pagination.firstIndex(3));
        assertEquals(25, pagination.lastIndexExclusive(3));
        assertEquals(5, pagination.countOn(3));
    }

    @Test
    void shouldRejectANonPositivePageSize() {
        assertThrows(IllegalArgumentException.class, () -> new Pagination(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pagination(10, -1));
    }

    @Test
    void shouldTreatANegativeTotalAsEmpty() {
        Pagination pagination = new Pagination(-5, 10);
        assertEquals(0, pagination.totalItems());
        assertEquals(1, pagination.pageCount());
    }
}
