package org.cubexmc.fawereplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

class FaweReplaceTextToMiniMessageStepTest {

    private final FaweReplaceTextToMiniMessageStep step = new FaweReplaceTextToMiniMessageStep(1, 2);
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    @Test
    void convertsSectionColorsBracePlaceholdersAndLiteralUsage() {
        // Arrange
        String input = "§e/{label} addrule <origin> <target> §7- Add replacement rule";

        // Act
        String converted = step.convert(input);

        // Assert
        assertEquals("<reset><yellow>/<label> addrule \\<origin> \\<target> <reset><gray>- Add replacement rule",
                converted);
    }

    @Test
    void preservesLegacyVisualsWhenColorResetsDecorations() {
        // Arrange
        String legacyTitle = "§6§lFAWE Replace §7- Command Help";

        // Act
        String converted = step.convert(legacyTitle);

        // Assert
        assertEquals(normalizeLegacy(legacyTitle), normalizeMini(converted));
    }

    @Test
    void convertsPrefixAndHexWithoutChangingText() {
        // Arrange
        String input = "§7[§6FAWEReplace§7] &#12ABEF{world}";

        // Act
        String converted = step.convert(input);

        // Assert
        assertEquals("<reset><gray>[<reset><gold>FAWEReplace<reset><gray>] <reset><#12ABEF><world>",
                converted);
        assertEquals(normalizeLegacy("§7[§6FAWEReplace§7] §x§1§2§A§B§E§Fworld"),
                normalizeMini(converted.replace("<world>", "world")));
    }

    private String normalizeLegacy(String input) {
        return legacy.serialize(legacy.deserialize(input));
    }

    private String normalizeMini(String input) {
        return legacy.serialize(miniMessage.deserialize(input));
    }
}
