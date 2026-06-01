package org.cubexmc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CubexTextTest {

    @Test
    void color_convertsNullToEmptyString() {
        // Arrange
        CubexText text = new CubexText();

        // Act
        String result = text.color(null);

        // Assert
        assertEquals("", result);
    }

    @Test
    void colorOrNull_preservesNull() {
        // Arrange
        CubexText text = new CubexText();

        // Act
        String result = text.colorOrNull(null);

        // Assert
        assertNull(result);
    }

    @Test
    void color_translatesLegacyAndHexColors() {
        // Arrange
        CubexText text = new CubexText();
        String expectedHex = net.md_5.bungee.api.ChatColor.of("#12ABEF").toString();

        // Act
        String result = text.color("&aGreen &#12ABEFHex");

        // Assert
        assertEquals("\u00a7aGreen " + expectedHex + "Hex", result);
    }
}
