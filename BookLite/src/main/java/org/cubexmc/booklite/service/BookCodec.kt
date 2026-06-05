package org.cubexmc.booklite.service

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.chat.ComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.booklite.BookLitePlugin
import org.cubexmc.booklite.config.ConfigManager
import org.cubexmc.booklite.lang.LanguageManager
import org.cubexmc.booklite.model.BookRecord

class BookCodec(
    private val plugin: BookLitePlugin?,
    private val keys: PdcKeys?,
    private val config: ConfigManager?,
) {
    fun createRecord(meta: BookMeta, fallbackAuthor: String?): BookRecord {
        val title = if (meta.hasTitle()) meta.title else "Untitled"
        val author = if (meta.hasAuthor()) meta.author else fallbackAuthor
        val pages = readStoredPages(meta).toMutableList()
        if (pages.isEmpty()) {
            pages.add("")
        }

        val payload = GSON.toJson(listOf(title ?: "", author ?: "", pages))
        val hash = sha256(payload)
        val now = System.currentTimeMillis()
        return BookRecord(null, hash, title, author, pages, now, now, null)
    }

    fun validate(record: BookRecord): ValidationResult {
        val currentConfig = requireNotNull(config) { "BookCodec config is required for validation" }
        if (record.totalPages() > currentConfig.getMaxPages()) {
            return ValidationResult.tooManyPages(record.totalPages(), currentConfig.getMaxPages())
        }

        var total = 0
        for (page in record.pages()) {
            val pageBytes = GSON.toJson(page).toByteArray(StandardCharsets.UTF_8).size
            if (pageBytes > currentConfig.getMaxPageJsonBytes()) {
                return ValidationResult.pageTooLarge(pageBytes, currentConfig.getMaxPageJsonBytes())
            }
            total += pageBytes
        }
        if (total > currentConfig.getMaxTotalJsonBytes()) {
            return ValidationResult.bookTooLarge(total, currentConfig.getMaxTotalJsonBytes())
        }
        return ValidationResult.success()
    }

    fun isWrittenBook(item: ItemStack?): Boolean =
        item != null && item.type == Material.WRITTEN_BOOK && item.itemMeta is BookMeta

    fun isBookLite(item: ItemStack?): Boolean {
        if (!isWrittenBook(item)) {
            return false
        }
        val meta = item?.itemMeta ?: return false
        val currentKeys = keys ?: return false
        return meta.persistentDataContainer.has(currentKeys.bookId(), PersistentDataType.STRING)
    }

    fun readBookId(item: ItemStack?): String? {
        if (!isWrittenBook(item)) {
            return null
        }
        val meta = item?.itemMeta ?: return null
        val currentKeys = keys ?: return null
        return meta.persistentDataContainer.get(currentKeys.bookId(), PersistentDataType.STRING)
    }

    fun readResolvableBookId(item: ItemStack?): String? {
        val id = readBookId(item)
        if (!id.isNullOrBlank()) {
            return id
        }
        if (!isWrittenBook(item)) {
            return null
        }
        val meta = item?.itemMeta ?: return null
        if (meta !is BookMeta) {
            return null
        }
        return legacyBookIdCandidate(if (meta.hasTitle()) meta.title else null, meta.lore)
    }

    fun readGeneration(item: ItemStack?): Int {
        if (!isWrittenBook(item)) {
            return 0
        }
        val itemMeta = item?.itemMeta ?: return 0
        val currentKeys = keys
        if (currentKeys != null) {
            val stored = itemMeta.persistentDataContainer.get(currentKeys.generation(), PersistentDataType.INTEGER)
            if (stored != null) {
                return max(0, stored)
            }
        }
        return if (itemMeta is BookMeta) generationToInt(itemMeta) else 0
    }

    fun createShell(record: BookRecord, generation: Int): ItemStack =
        createShell(record, generation, 1)

    fun createShell(record: BookRecord, generation: Int, amount: Int): ItemStack {
        val item = ItemStack(Material.WRITTEN_BOOK, max(1, amount))
        val meta = item.itemMeta as BookMeta
        applyShellMeta(meta, record, generation)
        item.itemMeta = meta
        return item
    }

    fun applyShellMeta(meta: BookMeta, record: BookRecord, generation: Int): BookMeta {
        meta.setTitle(visibleTitle(record))
        meta.setAuthor(visibleAuthor(record))
        meta.pages = listOf("")
        if (requireConfig().isPreserveGeneration()) {
            meta.generation = intToGeneration(generation)
        }
        val currentKeys = requireKeys()
        val pdc = meta.persistentDataContainer
        pdc.set(currentKeys.bookId(), PersistentDataType.STRING, record.id())
        pdc.set(currentKeys.generation(), PersistentDataType.INTEGER, max(0, generation))
        pdc.set(currentKeys.version(), PersistentDataType.INTEGER, PdcKeys.SCHEMA_VERSION)
        meta.lore = null
        return meta
    }

    fun createReadable(record: BookRecord?, generation: Int): ItemStack {
        if (record == null) {
            val lang: LanguageManager = requirePlugin().languageManager()
            return createSystemBook(lang.msg("book.missing_title"), lang.msg("book.missing_page"))
        }
        return createFullBook(record, generation)
    }

    fun createFullBook(record: BookRecord, generation: Int): ItemStack =
        createFullBook(record, generation, 1)

    fun createFullBook(record: BookRecord, generation: Int, amount: Int): ItemStack {
        val item = ItemStack(Material.WRITTEN_BOOK, max(1, amount))
        val meta = item.itemMeta as BookMeta
        meta.setTitle(visibleTitle(record))
        meta.setAuthor(visibleAuthor(record))
        applyStoredPages(meta, record.pages())
        if (requireConfig().isPreserveGeneration()) {
            meta.generation = intToGeneration(generation)
        }
        item.itemMeta = meta
        return item
    }

    fun generationToInt(meta: BookMeta?): Int {
        val generation = meta?.generation ?: return 0
        return when (generation) {
            BookMeta.Generation.ORIGINAL -> 0
            BookMeta.Generation.COPY_OF_ORIGINAL -> 1
            BookMeta.Generation.COPY_OF_COPY -> 2
            BookMeta.Generation.TATTERED -> 3
        }
    }

    fun intToGeneration(generation: Int): BookMeta.Generation =
        when (max(0, generation)) {
            1 -> BookMeta.Generation.COPY_OF_ORIGINAL
            2 -> BookMeta.Generation.COPY_OF_COPY
            3 -> BookMeta.Generation.TATTERED
            else -> BookMeta.Generation.ORIGINAL
        }

    fun canCopyGeneration(generation: Int): Boolean = generation < 2

    fun nextGeneration(generation: Int): Int = min(2, max(0, generation) + 1)

    private fun createSystemBook(title: String?, message: String?): ItemStack {
        val item = ItemStack(Material.WRITTEN_BOOK)
        val meta = item.itemMeta as BookMeta
        meta.setTitle(truncateBookTitle(title))
        meta.setAuthor("BookLite")
        meta.pages = listOf(if (message.isNullOrBlank()) "BookLite" else message)
        item.itemMeta = meta
        return item
    }

    fun visibleTitle(record: BookRecord): String =
        truncateBookTitle(if (record.title().isBlank()) "Untitled" else record.title())

    fun visibleAuthor(record: BookRecord): String =
        if (record.author().isBlank()) "Unknown" else record.author()

    fun readStoredPages(meta: BookMeta): List<String> {
        val componentPages = readComponentPages(meta)
        if (componentPages.isNotEmpty()) {
            return componentPages
        }
        return ArrayList(meta.pages)
    }

    private fun readComponentPages(meta: BookMeta): List<String> {
        return try {
            val spigot = meta.spigot() ?: return emptyList()
            val pages = spigot.pages
            if (pages.isEmpty()) {
                return emptyList()
            }
            val out = ArrayList<String>()
            for (page in pages) {
                out.add(ComponentSerializer.toString(page))
            }
            out
        } catch (exception: RuntimeException) {
            emptyList()
        } catch (error: LinkageError) {
            emptyList()
        }
    }

    fun applyStoredPages(meta: BookMeta, pages: List<String>?) {
        val safePages = if (pages.isNullOrEmpty()) listOf("") else pages
        try {
            val components = ArrayList<Array<BaseComponent>>()
            for (page in safePages) {
                components.add(toComponents(page))
            }
            meta.spigot().setPages(components)
        } catch (exception: RuntimeException) {
            meta.pages = safePages
        } catch (error: LinkageError) {
            meta.pages = safePages
        }
    }

    fun toComponents(page: String?): Array<BaseComponent> {
        val safe = page ?: ""
        if (looksLikeSerializedComponentPage(safe)) {
            try {
                return ComponentSerializer.parse(safe)
            } catch (ignored: RuntimeException) {
                // Fall through to legacy text for old records or literal JSON text.
            }
        }
        return TextComponent.fromLegacyText(safe)
    }

    fun looksLikeSerializedComponentPage(page: String?): Boolean {
        val trimmed = page?.trim() ?: ""
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    fun legacyBookIdCandidate(title: String?, lore: List<String>?): String? {
        if (lore != null) {
            for (line in lore) {
                val candidate = candidateAfterLabel(line, "BookLite ID:")
                if (candidate != null) {
                    return candidate
                }
            }
        }
        return candidateAfterLabel(title, "BookLite #")
    }

    private fun candidateAfterLabel(text: String?, label: String): String? {
        val clean = ChatColor.stripColor(text ?: "")?.trim() ?: ""
        val start = clean.indexOf(label)
        if (start < 0) {
            return null
        }
        var candidate = clean.substring(start + label.length).trim()
        val space = candidate.indexOf(' ')
        if (space >= 0) {
            candidate = candidate.substring(0, space)
        }
        if (candidate.startsWith("#")) {
            candidate = candidate.substring(1)
        }
        return if (looksLikeBookId(candidate)) candidate.lowercase(Locale.getDefault()) else null
    }

    private fun looksLikeBookId(candidate: String?): Boolean {
        if (candidate == null || candidate.length < 4 || candidate.length > 36) {
            return false
        }
        for (char in candidate) {
            if (char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F' || char == '-') {
                continue
            }
            return false
        }
        return true
    }

    private fun truncateBookTitle(title: String?): String {
        val safe = if (title.isNullOrBlank()) "Untitled" else title
        return if (safe.length <= TITLE_LIMIT) safe else safe.substring(0, TITLE_LIMIT)
    }

    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            HexFormat.of().formatHex(digest.digest(input.toByteArray(StandardCharsets.UTF_8)))
        } catch (exception: NoSuchAlgorithmException) {
            requirePlugin().logger.warning("SHA-256 is unavailable; using hashCode fallback.")
            input.hashCode().toString(16)
        }
    }

    private fun requirePlugin(): BookLitePlugin =
        requireNotNull(plugin) { "BookCodec plugin is required for this operation" }

    private fun requireKeys(): PdcKeys =
        requireNotNull(keys) { "BookCodec PDC keys are required for this operation" }

    private fun requireConfig(): ConfigManager =
        requireNotNull(config) { "BookCodec config is required for this operation" }

    class ValidationResult private constructor(
        private val ok: Boolean,
        private val key: String,
        private val actual: Int,
        private val max: Int,
    ) {
        fun ok(): Boolean = ok

        fun key(): String = key

        fun actual(): Int = actual

        fun max(): Int = max

        companion object {
            @JvmStatic
            fun success(): ValidationResult = ValidationResult(true, "", 0, 0)

            @JvmStatic
            fun tooManyPages(actual: Int, max: Int): ValidationResult =
                ValidationResult(false, "book.fail_too_many_pages", actual, max)

            @JvmStatic
            fun pageTooLarge(actual: Int, max: Int): ValidationResult =
                ValidationResult(false, "book.fail_page_too_large", actual, max)

            @JvmStatic
            fun bookTooLarge(actual: Int, max: Int): ValidationResult =
                ValidationResult(false, "book.fail_book_too_large", actual, max)
        }
    }

    private companion object {
        val GSON: Gson = Gson()
        const val TITLE_LIMIT: Int = 32
    }
}
