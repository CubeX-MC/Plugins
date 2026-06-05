package org.cubexmc.clarity;

import java.util.Arrays;
import java.util.Locale;

/** Player item containers that Clarity can scan or clean. */
public enum ItemScope {
    HAND("hand"),
    INVENTORY("inventory"),
    EQUIPMENT("equipment"),
    ENDER("ender"),
    ALL("all");

    private final String id;

    ItemScope(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static boolean isScope(String raw) {
        return parse(raw) != null;
    }

    public static ItemScope parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scope -> scope.id.equals(value))
                .findFirst()
                .orElse(null);
    }
}
