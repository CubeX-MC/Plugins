package org.cubexmc.cookbook.soulboundtool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoulboundReportTest {

    @Test
    fun `reports nothing found`() {
        assertEquals("没有发现属于别人的绑定物品。", SoulboundReport.summarise(emptyList()))
    }

    @Test
    fun `uses the singular wording for exactly one hit`() {
        assertEquals("发现 1 件属于别人的绑定物品:inventory[3]", SoulboundReport.summarise(listOf("inventory[3]")))
    }

    @Test
    fun `lists every slot label when there are several`() {
        val text = SoulboundReport.summarise(listOf("inventory[3]", "equipment[helmet]", "ender[0]"))

        assertTrue(text.startsWith("发现 3 件"))
        assertTrue(text.contains("equipment[helmet]"))
        assertTrue(text.contains("ender[0]"))
    }
}
