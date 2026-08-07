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

    @Test
    void preservePolicyKeepsExistingPlaceholderTokens() {
        // Arrange: a section that already used <name> placeholders before moving onto the i18n
        // service — escaping those would turn live placeholders into literal text.
        LegacyTextToMiniMessageStep preserving = new LegacyTextToMiniMessageStep(
                2, 3, LegacyTextToMiniMessageStep.AngleBrackets.PRESERVE);

        // Act
        String converted = preserving.convert("&#CFD8DC描述: &#FFFFFF<value>");

        // Assert
        assertEquals("<#CFD8DC>描述: <#FFFFFF><value>", converted);
    }

    @Test
    void preservePolicyStillConvertsLegacyColourCodes() {
        // Arrange
        LegacyTextToMiniMessageStep preserving = new LegacyTextToMiniMessageStep(
                2, 3, LegacyTextToMiniMessageStep.AngleBrackets.PRESERVE);

        // Act
        String converted = preserving.convert("&aDone &#69DB7C<count>");

        // Assert
        assertEquals("<green>Done <#69DB7C><count>", converted);
    }
}
