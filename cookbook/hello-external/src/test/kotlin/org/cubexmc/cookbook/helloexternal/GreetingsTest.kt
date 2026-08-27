package org.cubexmc.cookbook.helloexternal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GreetingsTest {

    @Test
    fun `replaces the name placeholder`() {
        val rendered = Greetings.render("&a你好, &f{name}&a!", "Angus")

        assertEquals("&a你好, &fAngus&a!", rendered)
    }

    @Test
    fun `replaces every occurrence of the placeholder`() {
        val rendered = Greetings.render("{name} -> {name}", "Steve")

        assertEquals("Steve -> Steve", rendered)
    }

    @Test
    fun `leaves a template without the placeholder untouched`() {
        val rendered = Greetings.render("&c没有占位符", "Angus")

        assertEquals("&c没有占位符", rendered)
    }
}
