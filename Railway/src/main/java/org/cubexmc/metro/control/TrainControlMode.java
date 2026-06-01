package org.cubexmc.metro.control;

public enum TrainControlMode {
    KINEMATIC,
    LEASHED,
    REACTIVE;

    public static TrainControlMode from(String raw, TrainControlMode fallback) {
        if (raw == null)
            return fallback;
        String v = raw.trim().toUpperCase();
        for (TrainControlMode m : values()) {
            if (m.name().equals(v))
                return m;
        }
        // also accept human-friendly strings
        if ("KINEMATIC".equalsIgnoreCase(raw))
            return KINEMATIC;
        if ("LEASHED".equalsIgnoreCase(raw))
            return LEASHED;
        if ("REACTIVE".equalsIgnoreCase(raw))
            return REACTIVE;
        return fallback;
    }
}
