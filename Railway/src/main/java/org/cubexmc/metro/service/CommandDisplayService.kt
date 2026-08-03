package org.cubexmc.metro.service

import java.util.function.Function

/**
 * Shared display helpers for command views.
 */
class CommandDisplayService {
    class Page<T>(
        private val items: List<T>,
        private val page: Int,
        private val totalPages: Int,
        private val totalItems: Int,
        private val pageSize: Int,
    ) {
        fun items(): List<T> = items
        fun page(): Int = page
        fun totalPages(): Int = totalPages
        fun totalItems(): Int = totalItems
        fun pageSize(): Int = pageSize
    }

    class HelpPage(
        private val header: String,
        private val lines: List<String>,
        private val page: Int,
        private val totalPages: Int,
    ) {
        fun header(): String = header
        fun lines(): List<String> = lines
        fun page(): Int = page
        fun totalPages(): Int = totalPages
    }

    class HelpSection(
        private val header: String,
        private val lines: List<String>,
    ) {
        fun header(): String = header
        fun lines(): List<String> = lines
    }

    fun <T> paginate(items: List<T>?, requestedPage: Int?): Page<T> = paginate(items, requestedPage, DEFAULT_PAGE_SIZE)

    fun <T> paginate(items: List<T>?, requestedPage: Int?, pageSize: Int): Page<T> {
        if (pageSize <= 0) {
            throw IllegalArgumentException("pageSize must be greater than zero")
        }

        val safeItems = items ?: emptyList()
        val totalItems = safeItems.size
        val totalPages = maxOf(1, kotlin.math.ceil(totalItems / pageSize.toDouble()).toInt())
        var page = requestedPage ?: 1
        if (page < 1) {
            page = 1
        } else if (page > totalPages) {
            page = totalPages
        }

        val start = (page - 1) * pageSize
        val end = minOf(start + pageSize, totalItems)
        val pageItems = if (totalItems == 0) emptyList() else java.util.List.copyOf(safeItems.subList(start, end))
        return Page(pageItems, page, totalPages, totalItems, pageSize)
    }

    fun helpPage(
        messageResolver: Function<String, String>,
        headerKey: String,
        lineKeys: List<String>,
        requestedPage: Int?,
    ): HelpPage {
        val keyPage = paginate(lineKeys, requestedPage)
        val lines = keyPage.items().map { key -> messageResolver.apply(key) }
        val header = pageHeader(messageResolver.apply(headerKey), keyPage)
        return HelpPage(header, lines, keyPage.page(), keyPage.totalPages())
    }

    fun pageHeader(header: String, page: Page<*>): String = header + " §e(" + page.page() + "/" + page.totalPages() + ")"

    fun helpSection(messageResolver: Function<String, String>, headerKey: String, lineKeys: List<String>?): HelpSection {
        val lines = (lineKeys ?: emptyList()).map { key -> messageResolver.apply(key) }
        return HelpSection(messageResolver.apply(headerKey), lines)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 8
    }
}
