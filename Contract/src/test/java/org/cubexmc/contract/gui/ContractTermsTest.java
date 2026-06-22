package org.cubexmc.contract.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractTermsTest {
    @Test
    void blankPreviewShowsMissingState() {
        assertEquals("未填写", ContractTerms.preview(null));
        assertEquals("未填写", ContractTerms.preview("   "));
    }

    @Test
    void previewCollapsesLinesAndTruncates() {
        String preview = ContractTerms.preview("Line one\nLine two\n" + "x".repeat(80));

        assertTrue(preview.contains(" / "));
        assertTrue(preview.endsWith("..."));
        assertTrue(preview.length() <= 48);
    }
}
