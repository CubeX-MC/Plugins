package org.cubexmc.mountlicense.model;

public enum VehicleFeature {
    REGISTER,
    PROTECT,
    SUMMON,
    PARK,
    INVENTORY_ACCESS,
    STATION,
    RENTAL;

    public static VehicleFeature fromYamlKey(String key) {
        if (key == null) return null;
        switch (key) {
            case "register": return REGISTER;
            case "protect": return PROTECT;
            case "summon": return SUMMON;
            case "park": return PARK;
            case "inventoryAccess": return INVENTORY_ACCESS;
            case "station": return STATION;
            case "rental": return RENTAL;
            default: return null;
        }
    }
}
