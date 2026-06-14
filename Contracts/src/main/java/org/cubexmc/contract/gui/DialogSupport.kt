package org.cubexmc.contract.gui

/**
 * Runtime probe for the Paper 1.21.6+ Dialog API. The check is a single `Class.forName`; it never
 * touches [DialogInputService], so that class (and its Dialog-API references) is only loaded on
 * servers that actually ship the Dialog API. On older Paper builds this stays `false` and the GUI
 * falls back to [ChatInputService] plus the inventory wizard/confirm screens.
 */
object DialogSupport {
    val available: Boolean by lazy {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog")
            true
        } catch (ex: Throwable) {
            false
        }
    }
}
