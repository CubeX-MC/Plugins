package org.cubexmc.booklite.model

import java.util.ArrayList
import java.util.Collections
import java.util.UUID

class BookRecord @JvmOverloads constructor(
    id: String?,
    contentHash: String,
    title: String?,
    author: String?,
    pages: List<String>?,
    private val createdAt: Long,
    private var updatedAt: Long,
    private var lastAccessedAt: Long? = null,
) {
    private val id: String = id ?: UUID.randomUUID().toString()
    private val contentHash: String = requireNotNull(contentHash)
    private val title: String = title ?: ""
    private val author: String = author ?: ""
    private val pages: List<String> = Collections.unmodifiableList(ArrayList(pages ?: listOf("")))

    fun id(): String = id

    fun contentHash(): String = contentHash

    fun title(): String = title

    fun author(): String = author

    fun pages(): List<String> = pages

    fun totalPages(): Int = pages.size

    fun createdAt(): Long = createdAt

    fun updatedAt(): Long = updatedAt

    fun lastAccessedAt(): Long? = lastAccessedAt

    fun setUpdatedAt(updatedAt: Long) {
        this.updatedAt = updatedAt
    }

    fun setLastAccessedAt(lastAccessedAt: Long?) {
        this.lastAccessedAt = lastAccessedAt
    }

    fun shortId(): String = if (id.length <= 8) id else id.substring(0, 8)
}
