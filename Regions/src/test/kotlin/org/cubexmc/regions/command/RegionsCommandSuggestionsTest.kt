package org.cubexmc.regions.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionsCommandSuggestionsTest {
    @Test
    fun `empty Paper arguments return ordinary player root suggestions`() {
        assertEquals(listOf("game", "help"), regionRootSuggestions(false, emptyArray()))
    }

    @Test
    fun `empty Paper arguments preserve sorted and bounded management suggestions`() {
        val suggestions = regionRootSuggestions(true, emptyArray()).orEmpty()

        assertEquals(suggestions.sorted(), suggestions)
        assertEquals(20, suggestions.size)
        assertTrue("game" in suggestions)
    }

    @Test
    fun `partial root argument is filtered safely`() {
        assertEquals(listOf("game"), regionRootSuggestions(false, arrayOf("GA")))
        assertEquals(listOf("withdraw"), regionRootSuggestions(true, arrayOf("with")))
    }
}
