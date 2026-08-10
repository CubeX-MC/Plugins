package org.cubexmc.contract.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractTermsTest {
    @Test
    void blankPreviewShowsCallerSuppliedEmptyLabel() {
        assertEquals("not set", ContractTerms.preview(null, "not set"));
        assertEquals("not set", ContractTerms.preview("   ", "not set"));
    }

    @Test
    void previewCollapsesLinesAndTruncates() {
        String preview = ContractTerms.preview("Line one\nLine two\n" + "x".repeat(80), "not set");

        assertTrue(preview.contains(" / "));
        assertTrue(preview.endsWith("..."));
        assertTrue(preview.length() <= 48);
    }
}
