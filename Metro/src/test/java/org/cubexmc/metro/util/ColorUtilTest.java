package org.cubexmc.metro.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColorUtilTest {

    @Test
    void shouldPassThroughNull() {
        assertNull(ColorUtil.colorize(null));
    }

    @Test
    void shouldPassThroughEmpty() {
        assertEquals("", ColorUtil.colorize(""));
    }

    @Test
    void shouldTranslateTraditionalColorCodes() {
        assertEquals("\u00a7aHello \u00a7cWorld", ColorUtil.colorize("&aHello &cWorld"));
    }

    @Test
    void shouldTranslateHexColorCodes() {
        String result = ColorUtil.colorize("&#FF0000Red Text");
        // ChatColor.of("#FF0000") produces a ChatColor that toString's to the net.md_5.bungee hex format
        assertSame(result.getClass(), String.class);
        assertTrue(result.contains("Red Text"));
        assertFalse(result.contains("&#FF0000"));
        assertTrue(result.contains("\u00a7"));
    }

    @Test
    void shouldHandleMixedColorCodes() {
        String result = ColorUtil.colorize("&aGreen &#FF0000Red");
        assertTrue(result.contains("Green"));
        assertTrue(result.contains("Red"));
        assertFalse(result.contains("&#FF0000"));
    }

    @Test
    void shouldLeavePlainTextUnchanged() {
        assertEquals("Hello World", ColorUtil.colorize("Hello World"));
    }
}
