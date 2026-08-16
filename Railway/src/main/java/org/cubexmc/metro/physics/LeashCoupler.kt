package org.cubexmc.metro.physics

import java.util.Locale
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Mob
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.SchedulerUtil
import kotlin.math.max
import kotlin.math.min

/** Manages invisible leashable entities between cars for visual coupling. */
open class LeashCoupler(
    private val plugin: Metro,
    private val train: TrainInstance,
) {

    private val dummies = ArrayList<LivingEntity>()
    private var offsetY = plugin.leashOffsetY

    open fun start() {
        cleanup()
        val cars = train.consist.getCars()
        if (cars.size < 2) {
            return
        }

        val type = parseEntityType(plugin.leashMobTypeRaw) ?: return

        for (index in 1 until cars.size) {
            val previous = cartOrNull(cars, index - 1)
            val current = cartOrNull(cars, index)
            if (previous == null || current == null || previous.isDead || current.isDead) {
                continue
            }

            val base = midpoint(previous.location, current.location)?.add(0.0, offsetY, 0.0)
                ?: throw NullPointerException("midpoint")
            val world = base.world ?: throw NullPointerException("world")
            val entityClass = type.entityClass?.asSubclass(LivingEntity::class.java)
                ?: throw NullPointerException("entity class")
            val dummy = world.spawn(base, entityClass) { entity ->
                entity.isInvisible = true
                entity.isSilent = true
                entity.isInvulnerable = true
                entity.setGravity(false)
                entity.isCollidable = false
                if (entity is Mob) {
                    entity.setAI(false)
                }
            }
            // Set leash holder to previous cart (visual rope between previous and dummy)
            try {
                dummy.setLeashHolder(previous)
            } catch (_: Throwable) {
                // Some server implementations reject this holder type; the visual dummy still remains harmless.
            }
            dummies.add(dummy)
        }
    }

    open fun update() {
        val cars = train.consist.getCars()
        if (dummies.isEmpty() || cars.size < 2) {
            return
        }
        val count = min(dummies.size, max(0, cars.size - 1))
        for (index in 0 until count) {
            val dummy = dummyOrNull(index)
            val previous = cartOrNull(cars, index)
            val current = cartOrNull(cars, index + 1)
            if (dummy == null || dummy.isDead || previous == null || current == null ||
                previous.isDead || current.isDead
            ) {
                continue
            }
            val target = midpoint(previous.location, current.location)?.add(0.0, offsetY, 0.0)
                ?: throw NullPointerException("midpoint")
            SchedulerUtil.teleportEntity(dummy, target)
            // Ensure leash is still tied to previous
            if (!dummy.isLeashed || dummy.leashHolder !== previous) {
                try {
                    dummy.setLeashHolder(previous)
                } catch (_: Throwable) {
                    // Keep updating the visual dummy even if the server rejects the leash operation.
                }
            }
        }
    }

    open fun cleanup() {
        for (entity in ArrayList(dummies)) {
            val leashEntity: LivingEntity? = entity
            if (leashEntity != null && !leashEntity.isDead) {
                try {
                    leashEntity.setLeashHolder(null)
                } catch (_: Throwable) {
                    // Removal below still cleans up the visual dummy.
                }
                leashEntity.remove()
            }
        }
        dummies.clear()
    }

    /** Preserve the old Java implementation's defensive null checks for list elements. */
    private fun cartOrNull(cars: List<Minecart>, index: Int): Minecart? = cars[index]

    /** Preserve the old Java implementation's defensive null check for dummy list elements. */
    private fun dummyOrNull(index: Int): LivingEntity? = dummies[index]

    companion object {
        @JvmStatic
        private fun midpoint(first: Location?, second: Location?): Location? {
            if (first == null) {
                return second
            }
            if (second == null) {
                return first
            }
            val firstVector = first.toVector()
            val secondVector = second.toVector()
            val midpoint: Vector = firstVector.clone().add(secondVector).multiply(0.5)
            return Location(first.world, midpoint.x, midpoint.y, midpoint.z)
        }

        @JvmStatic
        private fun parseEntityType(raw: String?): EntityType? {
            if (raw == null) {
                return null
            }
            return try {
                val type = EntityType.valueOf(raw.trim().uppercase(Locale.getDefault()))
                if (type.isAlive) type else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
