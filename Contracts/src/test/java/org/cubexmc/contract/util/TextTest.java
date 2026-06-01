package org.cubexmc.contract.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {
    @Test
    void stripControlRemovesControlButKeepsTabAndNewline() {
        String input = "helloworld\nsecond\tline";
        String result = Text.stripControl(input);
        assertTrue(result.contains("\n"));
        assertTrue(result.contains("\t"));
        assertTrue(result.startsWith("hello"));
        assertTrue(result.contains("world"));
        assertEquals(-1, result.indexOf(''));
    }

    @Test
    void stripControlNullReturnsEmpty() {
        assertEquals("", Text.stripControl(null));
    }

    @Test
    void stripControlTrims() {
        assertEquals("hello", Text.stripControl("  hello  "));
    }
}
