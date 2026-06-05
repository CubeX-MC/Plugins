package org.cubexmc.mountlicense.model

import org.bukkit.entity.EntityType
import java.util.Collections
import java.util.EnumSet

class VehicleProfile @JvmOverloads constructor(
    private val id: String,
    entityTypes: Set<EntityType>,
    features: Set<VehicleFeature>,
    private val requiresTamedOwner: Boolean,
    private val requiresSaddle: Boolean = false,
) {
    private val entityTypes: Set<EntityType> =
        if (entityTypes.isEmpty()) {
            Collections.unmodifiableSet(EnumSet.noneOf(EntityType::class.java))
        } else {
            Collections.unmodifiableSet(EnumSet.copyOf(entityTypes))
        }

    private val features: Set<VehicleFeature> =
        if (features.isEmpty()) {
            Collections.unmodifiableSet(EnumSet.noneOf(VehicleFeature::class.java))
        } else {
            Collections.unmodifiableSet(EnumSet.copyOf(features))
        }

    fun id(): String = id

    fun entityTypes(): Set<EntityType> = entityTypes

    fun features(): Set<VehicleFeature> = features

    fun requiresTamedOwner(): Boolean = requiresTamedOwner

    fun requiresSaddle(): Boolean = requiresSaddle

    fun matches(type: EntityType?): Boolean = type != null && entityTypes.contains(type)

    fun has(f: VehicleFeature): Boolean = features.contains(f)
}
