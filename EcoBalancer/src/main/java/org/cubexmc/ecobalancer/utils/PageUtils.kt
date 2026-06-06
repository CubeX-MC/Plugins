package org.cubexmc.ecobalancer.utils

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import kotlin.math.min

object PageUtils {
    @JvmStatic
    fun calculateTotalPages(totalItems: Int, pageSize: Int): Int = (totalItems + pageSize - 1) / pageSize

    @JvmStatic
    fun isValidPage(page: Int, totalPages: Int): Boolean = page >= 1 && page <= totalPages

    @JvmStatic
    fun getStartIndex(page: Int, pageSize: Int): Int = (page - 1) * pageSize

    @JvmStatic
    fun getEndIndex(page: Int, pageSize: Int, totalItems: Int): Int =
        min(getStartIndex(page, pageSize) + pageSize, totalItems)

    @JvmStatic
    fun createPageNavigation(
        langConfig: FileConfiguration,
        currentPage: Int,
        totalPages: Int,
        commandFormat: String,
        prefix: String,
    ): TextComponent {
        val placeholders: MutableMap<String, String> = HashMap()
        placeholders["page"] = currentPage.toString()
        placeholders["total"] = totalPages.toString()

        val prevPage = TextComponent()
        val nextPage = TextComponent()

        if (currentPage > 1) {
            prevPage.text = MessageUtils.formatMessage(langConfig, "messages.prev_page", null, prefix)
            prevPage.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, String.format(commandFormat, currentPage - 1))
        } else {
            prevPage.text = MessageUtils.formatMessage(langConfig, "messages.no_prev_page", null, prefix)
        }

        if (currentPage < totalPages) {
            nextPage.text = MessageUtils.formatMessage(langConfig, "messages.next_page", null, prefix)
            nextPage.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, String.format(commandFormat, currentPage + 1))
        } else {
            nextPage.text = MessageUtils.formatMessage(langConfig, "messages.no_next_page", null, prefix)
        }

        placeholders["prev"] = prevPage.toPlainText()
        placeholders["next"] = nextPage.toPlainText()

        return MessageUtils.formatComponent(
            langConfig,
            "messages.page_navigation",
            placeholders,
            arrayOf("prev", "next"),
            arrayOf(prevPage, nextPage),
            prefix,
        )
    }

    @JvmStatic
    fun <T> renderPagination(
        sender: CommandSender,
        items: List<T>,
        pageSize: Int,
        currentPage: Int,
        renderer: ItemRenderer<T>,
        headerMessagePath: String?,
        footerMessagePath: String?,
        navigationMessagePath: String?,
        commandFormat: String,
        langConfig: FileConfiguration,
        invalidPageMessagePath: String,
        prefix: String,
        extraPlaceholders: Map<String, String>?,
    ) {
        val totalPages = calculateTotalPages(items.size, pageSize)

        if (!isValidPage(currentPage, totalPages)) {
            sender.sendMessage(MessageUtils.formatMessage(langConfig, invalidPageMessagePath, null, prefix))
            return
        }

        val start = getStartIndex(currentPage, pageSize)
        val end = getEndIndex(currentPage, pageSize, items.size)

        if (headerMessagePath != null) {
            val headerPlaceholders: MutableMap<String, String> = HashMap()
            if (extraPlaceholders != null) {
                headerPlaceholders.putAll(extraPlaceholders)
            }
            sender.sendMessage(MessageUtils.formatMessage(langConfig, headerMessagePath, headerPlaceholders, prefix))
        }

        for (i in start until end) {
            renderer.render(sender, items[i], i)
        }

        if (navigationMessagePath != null) {
            val navigationComponent = createPageNavigation(langConfig, currentPage, totalPages, commandFormat, prefix)
            sender.spigot().sendMessage(navigationComponent)
        }

        if (footerMessagePath != null) {
            sender.sendMessage(MessageUtils.formatMessage(langConfig, footerMessagePath, null, prefix))
        }
    }

    fun interface ItemRenderer<T> {
        fun render(sender: CommandSender, item: T, index: Int)
    }
}
