package org.cubexmc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LegacyTextToMiniMessageStepTest {

    private final LegacyTextToMiniMessageStep step = new LegacyTextToMiniMessageStep(1, 2);

    @Test
    void convertsLegacyColorsStylesResetAndHex() {
        // Arrange
        String input = "&cRed &lBold &rReset &#12ABEFHex";

        // Act
        String converted = step.convert(input);

        // Assert
        assertEquals("<red>Red <bold>Bold <reset>Reset <#12ABEF>Hex", converted);
    }

    @Test
    void convertsBookLitePlaceholdersToMiniMessageTags() {
        // Arrange
        String input = "{prefix}&a%name% #%short_id%";

        // Act
        String converted = step.convert(input);

        // Assert
        assertEquals("<prefix><green><name> #<short_id>", converted);
    }

    @Test
    void escapesLiteralAngleBracketUsage() {
        // Arrange
        String input = "&e/booklite info <id>";

        // Act
        String converted = step.convert(input);

        // Assert
        assertEquals("<yellow>/booklite info \\<id>", converted);
    }
}
