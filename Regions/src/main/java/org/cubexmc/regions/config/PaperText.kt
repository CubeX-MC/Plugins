package org.cubexmc.regions.config

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.cubexmc.config.LegacyTextToMiniMessageStep

object PaperText {
    private val miniMessage = MiniMessage.miniMessage()
    private val legacyConverter = LegacyTextToMiniMessageStep(0, 1)
    private val legacyFormat = Regex("(?i)[&§](?:#[0-9a-f]{6}|[0-9a-fk-or])")

    fun parse(input: String): Component {
        val normalized = input.replace('§', '&')
        val miniMessageInput = if (legacyFormat.containsMatchIn(input)) {
            legacyConverter.convert(normalized)
        } else {
            normalized
        }
        return miniMessage.deserialize(miniMessageInput)
    }
}

fun Audience.sendLegacyMessage(message: String) {
    sendMessage(PaperText.parse(message))
}
