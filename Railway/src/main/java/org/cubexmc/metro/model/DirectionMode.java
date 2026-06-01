package org.cubexmc.metro.model;

public enum DirectionMode {
    BI_DIRECTIONAL,
    CIRCULAR,
    SINGLE_DIRECTION;

    public static DirectionMode from(String s, DirectionMode def) {
        if (s == null)
            return def;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
