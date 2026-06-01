package org.cubexmc.ecobalancer.tax;

import java.util.Locale;

public enum DebtMode {
    SKIP,
    DRAIN,
    ALLOW_NEGATIVE,
    INHERIT;

    public static DebtMode fromConfig(String value, DebtMode fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        switch (normalized) {
            case "skip":
                return SKIP;
            case "drain":
                return DRAIN;
            case "allow_negative":
                return ALLOW_NEGATIVE;
            case "inherit":
                return INHERIT;
            default:
                return fallback;
        }
    }

    public String toConfigValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
