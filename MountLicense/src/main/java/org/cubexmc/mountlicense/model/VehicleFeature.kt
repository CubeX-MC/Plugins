package org.cubexmc.mountlicense.model

enum class VehicleFeature {
    REGISTER,
    PROTECT,
    SUMMON,
    PARK,
    INVENTORY_ACCESS,
    STATION,
    RENTAL;

    companion object {
        @JvmStatic
        fun fromYamlKey(key: String?): VehicleFeature? =
            when (key) {
                "register" -> REGISTER
                "protect" -> PROTECT
                "summon" -> SUMMON
                "park" -> PARK
                "inventoryAccess" -> INVENTORY_ACCESS
                "station" -> STATION
                "rental" -> RENTAL
                else -> null
            }
    }
}
