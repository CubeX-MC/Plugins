package org.cubexmc.metro.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player
import java.time.Duration

/**
 * Utility helpers for sending Adventure-based UI elements (titles, action bars, etc.).
 */
object AdventureUtil {

    private const val MILLIS_PER_TICK = 50L

    private val LEGACY_AMPERSAND: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()
    private val LEGACY_SECTION: LegacyComponentSerializer = LegacyComponentSerializer.legacySection()

    @JvmStatic
    fun component(text: String?): Component {
        if (text.isNullOrEmpty()) {
            return Component.empty()
        }
        val normalized = text.replace('§', '&')
        return LEGACY_AMPERSAND.deserialize(normalized)
    }

    @JvmStatic
    fun buildTitle(
        title: String?,
        subtitle: String?,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    ): Title =
        Title.title(
            component(title),
            component(subtitle),
            Title.Times.times(
                ticksToDuration(fadeInTicks),
                ticksToDuration(stayTicks),
                ticksToDuration(fadeOutTicks),
            ),
        )

    @JvmStatic
    fun sendTitle(
        player: Player?,
        title: String?,
        subtitle: String?,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    ) {
        if (player == null) {
            return
        }
        player.sendTitle(legacyText(title), legacyText(subtitle), fadeInTicks, stayTicks, fadeOutTicks)
    }

    @JvmStatic
    fun sendActionBar(player: Player?, message: String?) {
        if (player == null || message.isNullOrEmpty()) {
            return
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(legacyText(message)))
    }

    @JvmStatic
    fun clearTitle(player: Player?) {
        if (player == null) {
            return
        }
        player.resetTitle()
    }

    @JvmStatic
    fun legacyText(text: String?): String = LEGACY_SECTION.serialize(component(text))

    private fun ticksToDuration(ticks: Int): Duration {
        if (ticks <= 0) {
            return Duration.ZERO
        }
        return Duration.ofMillis(ticks * MILLIS_PER_TICK)
    }
}
