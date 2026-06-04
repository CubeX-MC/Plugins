package org.cubexmc.metro.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetroTextRendererTest {

    @Test
    void convertsLegacyColorsBracePlaceholdersAndLiteralUsage() {
        String converted = MetroTextRenderer.convertLegacyTemplate(
                "&cUsage: /m line create <line_id> {line_name} %1");

        assertEquals("<red>Usage: /m line create \\<line_id> <line_name> <arg1>", converted);
    }

    @Test
    void rendersVisualEquivalentLegacyText() {
        String oldTemplate = "&6Route: &f{line_name} &#55AAFF%1";
        String newTemplate = MetroTextRenderer.convertLegacyTemplate(oldTemplate);

        String oldRendered = ColorUtil.colorize(oldTemplate
                .replace("{line_name}", "Blue Line")
                .replace("%1", "A"));
        String newRendered = MetroTextRenderer.render(newTemplate,
                Map.of("line_name", "Blue Line", "arg1", "A"));

        assertEquals(normalizeColorCodes(oldRendered), normalizeColorCodes(newRendered));
    }

    @Test
    void preservesUnresolvedPlaceholdersForLegacyCallSurface() {
        String rendered = MetroTextRenderer.renderPreservingPlaceholders("<green>Line <line_id>");

        assertEquals("§aLine {line_id}", rendered);
    }

    @Test
    void configLineColorCodeKeepsLegacyVisualBehavior() {
        String oldTemplate = "&fRight-click rail &7[&r{line_color_code}{line}&7]";
        String newTemplate = MetroTextRenderer.convertLegacyTemplate(oldTemplate);

        String oldRendered = ColorUtil.colorize(oldTemplate
                .replace("{line_color_code}", "&a")
                .replace("{line}", "East"));
        String renderedTemplate = MetroTextRenderer.renderPreservingPlaceholders(newTemplate);
        String newRendered = ColorUtil.colorize(renderedTemplate
                .replace("{line_color_code}", "&a")
                .replace("{line}", "East"));

        assertEquals(normalizeColorCodes(oldRendered), normalizeColorCodes(newRendered));
    }

    private static String normalizeColorCodes(String input) {
        StringBuilder normalized = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            normalized.append(current);
            if (current == '§' && i + 1 < input.length()) {
                normalized.append(Character.toLowerCase(input.charAt(++i)));
            }
        }
        return normalized.toString();
    }
}
