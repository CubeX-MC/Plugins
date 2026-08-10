package org.cubexmc.regions.mode

import org.bukkit.command.CommandSender
import org.cubexmc.regions.RegionsPlugin

/**
 * Resolves a gameplay message from the active language file.
 *
 * Mode services broadcast to whoever is in the match rather than to one command sender, so they need
 * the resolved string rather than a send helper bound to a single recipient.
 */
internal fun RegionsPlugin.gameText(key: String, placeholders: Map<String, String> = emptyMap()): String =
    lang().message(key, placeholders)

/** Sends a line already resolved by [gameText] to one participant. */
internal fun RegionsPlugin.sendGame(recipient: CommandSender, message: String) {
    lang().sendRaw(recipient, message)
}
