package org.cubexmc.cookbook.welcomeback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WelcomesTest {

    @Test
    fun `greets a returning player`() {
        assertEquals("&a欢迎回来, &fAngus&a!", Welcomes.render("Angus", firstJoin = false))
    }

    @Test
    fun `greets a first-time player differently`() {
        assertEquals("&e欢迎新玩家 &fSteve&e!", Welcomes.render("Steve", firstJoin = true))
    }
}
