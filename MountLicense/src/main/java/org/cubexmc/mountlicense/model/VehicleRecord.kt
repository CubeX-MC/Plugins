package org.cubexmc.mountlicense.model

import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Objects
import java.util.UUID

class VehicleRecord(
    vehicleId: UUID,
    entityUuid: UUID,
    private val entityType: String?,
    ownerUuid: UUID,
    private val profile: String?,
) {
    private val vehicleId: UUID = Objects.requireNonNull(vehicleId)
    private val entityUuid: UUID = Objects.requireNonNull(entityUuid)
    private val ownerUuid: UUID = Objects.requireNonNull(ownerUuid)
    private var plate: String = defaultPlate(this.vehicleId)
    private var displayName: String? = null
    private var state: VehicleState = VehicleState.ACTIVE
    private var world: String? = null
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var yaw = 0.0f
    private var pitch = 0.0f
    private var stationId: String? = null
    private var createdAt: Long = System.currentTimeMillis()
    private var lastSeenAt: Long = createdAt
    private val trustees: MutableSet<UUID> = Collections.synchronizedSet(LinkedHashSet())

    fun vehicleId(): UUID = vehicleId

    fun entityUuid(): UUID = entityUuid

    fun entityType(): String? = entityType

    fun ownerUuid(): UUID = ownerUuid

    fun profile(): String? = profile

    fun plate(): String = plate

    fun setPlate(plate: String?) {
        this.plate = normalizePlate(plate, vehicleId)
    }

    fun displayName(): String? = displayName

    fun setDisplayName(displayName: String?) {
        this.displayName = displayName
    }

    fun state(): VehicleState = state

    fun setState(state: VehicleState?) {
        this.state = state ?: VehicleState.ACTIVE
    }

    fun world(): String? = world

    fun x(): Double = x

    fun y(): Double = y

    fun z(): Double = z

    fun yaw(): Float = yaw

    fun pitch(): Float = pitch

    fun setLocation(world: String?, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.world = world
        this.x = x
        this.y = y
        this.z = z
        this.yaw = yaw
        this.pitch = pitch
    }

    fun stationId(): String? = stationId

    fun setStationId(stationId: String?) {
        this.stationId = stationId
    }

    fun createdAt(): Long = createdAt

    fun setCreatedAt(t: Long) {
        createdAt = t
    }

    fun lastSeenAt(): Long = lastSeenAt

    fun setLastSeenAt(t: Long) {
        lastSeenAt = t
    }

    fun shortId(): String = plate

    fun trustees(): MutableSet<UUID> = trustees

    fun addTrustee(playerUuid: UUID?): Boolean {
        if (playerUuid == null) return false
        if (playerUuid == ownerUuid) return false
        return trustees.add(playerUuid)
    }

    fun removeTrustee(playerUuid: UUID?): Boolean {
        if (playerUuid == null) return false
        return trustees.remove(playerUuid)
    }

    fun isTrustee(playerUuid: UUID?): Boolean = playerUuid != null && trustees.contains(playerUuid)

    private companion object {
        fun normalizePlate(raw: String?, fallbackId: UUID): String {
            if (raw == null || raw.trim().isEmpty()) {
                return defaultPlate(fallbackId)
            }
            return raw.trim().uppercase(Locale.getDefault())
        }

        fun defaultPlate(vehicleId: UUID): String {
            val compact = vehicleId.toString().replace("-", "").uppercase(Locale.getDefault())
            return compact.substring(0, 3) + "-" + compact.substring(3, 6)
        }
    }
}
