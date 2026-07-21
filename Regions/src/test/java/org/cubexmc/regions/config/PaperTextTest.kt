package org.cubexmc.regions.config

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaperTextTest {
    @Test
    fun `legacy and hex formatting is converted to an Adventure component`() {
        val component = PaperText.parse("&aRegions &#69DB7CTest")

        assertEquals(
            "Regions Test",
            PlainTextComponentSerializer.plainText().serialize(component),
        )
    }

    @Test
    fun `section sign input remains compatible with existing language files`() {
        val component = PaperText.parse("§c拒绝访问")

        assertEquals(
            "拒绝访问",
            PlainTextComponentSerializer.plainText().serialize(component),
        )
    }

    @Test
    fun `migrated MiniMessage input is parsed natively`() {
        val component = PaperText.parse("<#69DB7C>Regions <gray>ready")

        assertEquals(
            "Regions ready",
            PlainTextComponentSerializer.plainText().serialize(component),
        )
    }
}
