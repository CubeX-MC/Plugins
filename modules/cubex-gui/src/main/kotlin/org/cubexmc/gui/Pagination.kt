package org.cubexmc.gui

/**
 * Page arithmetic for list-backed screens and paged command output.
 *
 * Pages are **1-based**, which is what every existing call site and every `/… list <页码>` command in
 * this repo already uses. An empty list still has one (empty) page, so callers never have to special-case
 * "no results" before rendering a header.
 *
 * Deliberately free of Bukkit types: the same instance serves inventory GUIs and chat pagination.
 */
class Pagination(totalItems: Int, pageSize: Int) {
    init {
        require(pageSize > 0) { "pageSize must be positive: $pageSize" }
    }

    private val total: Int = totalItems.coerceAtLeast(0)
    private val size: Int = pageSize

    /** Always at least 1, so an empty list renders as "第 1 页 / 共 1 页" rather than "共 0 页". */
    private val pages: Int = if (total == 0) 1 else (total + size - 1) / size

    fun totalItems(): Int = total

    fun pageSize(): Int = size

    fun pageCount(): Int = pages

    /** Forces [page] into `1..pageCount`. Out-of-range input clamps instead of throwing. */
    fun clamp(page: Int): Int = page.coerceIn(1, pages)

    fun hasPrevious(page: Int): Boolean = clamp(page) > 1

    fun hasNext(page: Int): Boolean = clamp(page) < pages

    /** Index of the first item on [page], or [totalItems] when the page is empty. */
    fun firstIndex(page: Int): Int = ((clamp(page) - 1) * size).coerceAtMost(total)

    /** Exclusive index just past the last item on [page]. */
    fun lastIndexExclusive(page: Int): Int = (firstIndex(page) + size).coerceAtMost(total)

    /** How many items actually land on [page]; the final page is usually short. */
    fun countOn(page: Int): Int = lastIndexExclusive(page) - firstIndex(page)

    /** The slice of [items] belonging to [page]. Safe when [items] is shorter than [totalItems]. */
    fun <T> slice(items: List<T>, page: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val from = firstIndex(page).coerceAtMost(items.size)
        val to = lastIndexExclusive(page).coerceAtMost(items.size)
        if (from >= to) return emptyList()
        return items.subList(from, to)
    }

    companion object {
        /** Convenience for the common `Pagination(list.size, pageSize)` call. */
        @JvmStatic
        fun of(items: Collection<*>, pageSize: Int): Pagination = Pagination(items.size, pageSize)
    }
}
