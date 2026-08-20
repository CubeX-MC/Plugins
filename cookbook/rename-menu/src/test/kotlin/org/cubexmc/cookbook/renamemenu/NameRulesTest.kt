package org.cubexmc.cookbook.renamemenu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NameRulesTest {

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("传家宝", NameRules.sanitise("  传家宝  "))
    }

    @Test
    fun `rejects an input that is only whitespace`() {
        assertNull(NameRules.sanitise("   "))
        assertNull(NameRules.sanitise(""))
    }

    @Test
    fun `caps the name at the maximum length`() {
        val long = "x".repeat(NameRules.MAX_LENGTH + 10)

        assertEquals(NameRules.MAX_LENGTH, NameRules.sanitise(long)!!.length)
    }

    @Test
    fun `keeps a name that is exactly at the limit`() {
        val exact = "y".repeat(NameRules.MAX_LENGTH)

        assertEquals(exact, NameRules.sanitise(exact))
    }
}
