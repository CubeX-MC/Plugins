package org.cubexmc.mountlicense.model;

public enum VehicleState {
    ACTIVE,
    PARKED,
    LOCKED,
    RENTAL,
    MISSING,
    RELEASED;

    public static VehicleState fromString(String raw, VehicleState fallback) {
        if (raw == null) return fallback;
        try {
            return VehicleState.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
