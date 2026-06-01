package org.cubexmc.contract.gui;

import java.util.Locale;

/**
 * Signature token validation for the anvil confirmation layer. A signature is
 * accepted when the player types their own name or one of the agreement words.
 * Kept as a small pure function so the rule can be unit tested.
 */
public final class Signature {
    private Signature() {
    }

    public static boolean matches(String playerName, String input) {
        if (playerName == null || input == null) {
            return false;
        }
        String token = input.trim();
        if (token.isEmpty()) {
            return false;
        }
        if (token.equalsIgnoreCase(playerName.trim())) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return token.equals("同意") || lower.equals("agree") || lower.equals("confirm");
    }
}
