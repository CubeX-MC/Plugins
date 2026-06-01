package org.cubexmc.contract.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureTest {

    @Test
    void acceptsOwnNameCaseInsensitive() {
        assertTrue(Signature.matches("Steve", "steve"));
        assertTrue(Signature.matches("Steve", "STEVE"));
        assertTrue(Signature.matches("Steve", "  Steve  "));
    }

    @Test
    void acceptsAgreementWords() {
        assertTrue(Signature.matches("Steve", "同意"));
        assertTrue(Signature.matches("Steve", "agree"));
        assertTrue(Signature.matches("Steve", "CONFIRM"));
    }

    @Test
    void rejectsBlankOrWrongInput() {
        assertFalse(Signature.matches("Steve", ""));
        assertFalse(Signature.matches("Steve", "   "));
        assertFalse(Signature.matches("Steve", "Alex"));
        assertFalse(Signature.matches("Steve", null));
        assertFalse(Signature.matches(null, "Steve"));
    }
}
