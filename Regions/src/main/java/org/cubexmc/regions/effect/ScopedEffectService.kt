package org.cubexmc.regions.effect

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectLease
import org.cubexmc.regions.model.PlayerStateSnapshot
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.service.ServiceResult
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class ScopedEffectService(private val plugin: RegionsPlugin) {
    private val effectTypes: MutableSet<String> = LinkedHashSet()
    private val leases: MutableMap<UUID, EffectLease> = ConcurrentHashMap()

    fun register(type: String) {
        if (type.isNotBlank()) {
            effectTypes.add(type.lowercase())
        }
    }

    fun registerDefaults() {
        listOf(
            "scale",
            "potion",
            "walk_speed",
            "fly_speed",
            "allow_flight",
            "glowing",
            "collision",
            "health_scale",
            "temporary_inventory",
            "scoreboard",
            "bossbar",
            "permission_attachment",
            "invisibility_suppression",
        ).forEach { register(it) }
    }

    fun isRegistered(type: String): Boolean = effectTypes.contains(type.lowercase())

    fun allTypes(): Set<String> = effectTypes.toSet()

    fun apply(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        if (!isRegistered(config.type)) {
            return ServiceResult.fail("Unknown effect type ${config.type}")
        }
        val result = when (config.type.lowercase(Locale.ROOT)) {
            "scale" -> applyScale(player, region, config)
            "potion" -> applyPotion(player, region, config)
            "allow_flight" -> applyAllowFlight(player, region, config)
            "walk_speed" -> applyWalkSpeed(player, region, config)
            "fly_speed" -> applyFlySpeed(player, region, config)
            "invisibility_suppression" -> applyInvisibilitySuppression(player, region, config)
            else -> ServiceResult.fail("Effect ${config.type} is registered but not implemented yet.")
        }
        if (!result.success) {
            plugin.logger.warning("Failed to apply effect ${config.type} to ${player.name} in ${region.id}: ${result.reason}")
        }
        return result
    }

    fun track(lease: EffectLease) {
        leases[lease.id] = lease
    }

    fun restoreLease(leaseId: UUID): ServiceResult {
        val lease = leases.remove(leaseId) ?: return ServiceResult.ok()
        val player = plugin.server.getPlayer(lease.playerId) ?: return ServiceResult.ok()
        return restore(player, lease)
    }

    fun cleanupPlayer(player: Player, reason: String): Int {
        val playerLeases = leases.values.filter { it.playerId == player.uniqueId }
        for (lease in playerLeases) {
            restoreLease(lease.id)
        }
        if (playerLeases.isNotEmpty()) {
            plugin.logger.fine("Cleaned ${playerLeases.size} effect lease(s) for ${player.name}: $reason")
        }
        return playerLeases.size
    }

    fun cleanupRegion(player: Player, regionId: String, reason: String): Int {
        val playerLeases = leases.values.filter { it.playerId == player.uniqueId && it.regionId == regionId }
        for (lease in playerLeases) {
            restoreLease(lease.id)
        }
        if (playerLeases.isNotEmpty()) {
            plugin.logger.fine("Cleaned ${playerLeases.size} effect lease(s) for ${player.name} in $regionId: $reason")
        }
        return playerLeases.size
    }

    fun refreshAll() {
        for (lease in leases.values.toList()) {
            val player = plugin.server.getPlayer(lease.playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                refreshLease(player, lease)
            })
        }
    }

    fun refreshPlayer(player: Player) {
        for (lease in leases.values.toList()) {
            if (lease.playerId == player.uniqueId) {
                refreshLease(player, lease)
            }
        }
    }

    private fun refreshLease(player: Player, lease: EffectLease) {
        if (lease.effectType.equals("potion", ignoreCase = true)) {
            refreshPotion(player, lease)
        } else if (lease.effectType.equals("invisibility_suppression", ignoreCase = true)) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY)
        }
    }

    private fun applyScale(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        val value = config.values["value"]?.toDoubleOrNull()
            ?: return ServiceResult.fail("scale effect requires value.")
        val minScale = plugin.config.getDouble("effects.scale.min", 0.1)
        val maxScale = plugin.config.getDouble("effects.scale.max", 4.0)
        val safeValue = min(max(value, minScale), maxScale)
        val instance = scaleAttributeInstance(player)
            ?: return ServiceResult.fail("Current server does not expose the scale attribute.")
        val previous = instance.baseValue
        instance.baseValue = safeValue
        val lease = EffectLease(
            UUID.randomUUID(),
            player.uniqueId,
            region.id,
            "scale",
            config.scope,
            System.currentTimeMillis(),
            null,
            PlayerStateSnapshot(mapOf("base" to previous.toString())),
        )
        track(lease)
        return ServiceResult.ok()
    }

    private fun applyPotion(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        val name = config.values["effect"] ?: config.values["name"]
            ?: return ServiceResult.fail("potion effect requires effect.")
        val type = PotionEffectType.getByName(name.uppercase(Locale.ROOT))
            ?: return ServiceResult.fail("Unknown potion effect $name.")
        val previous = player.getPotionEffect(type)
        val duration = config.values["duration-ticks"]?.toIntOrNull()
            ?: plugin.config.getInt("effects.default-duration-ticks", 400)
        val amplifier = config.values["amplifier"]?.toIntOrNull() ?: 0
        val ambient = config.values["ambient"]?.toBooleanStrictOrNull() ?: false
        val particles = config.values["particles"]?.toBooleanStrictOrNull() ?: true
        val icon = config.values["icon"]?.toBooleanStrictOrNull() ?: true
        player.addPotionEffect(PotionEffect(type, duration, amplifier, ambient, particles, icon))
        val snapshot = LinkedHashMap<String, String>()
        snapshot["effect"] = type.name
        if (previous != null) {
            snapshot["had"] = "true"
            snapshot["duration"] = previous.duration.toString()
            snapshot["amplifier"] = previous.amplifier.toString()
            snapshot["ambient"] = previous.isAmbient.toString()
            snapshot["particles"] = previous.hasParticles().toString()
            snapshot["icon"] = previous.hasIcon().toString()
        } else {
            snapshot["had"] = "false"
        }
        val lease = EffectLease(
            UUID.randomUUID(),
            player.uniqueId,
            region.id,
            "potion",
            config.scope,
            System.currentTimeMillis(),
            null,
            PlayerStateSnapshot(snapshot),
            mapOf(
                "effect" to type.name,
                "duration" to duration.toString(),
                "amplifier" to amplifier.toString(),
                "ambient" to ambient.toString(),
                "particles" to particles.toString(),
                "icon" to icon.toString(),
            ),
        )
        track(lease)
        return ServiceResult.ok()
    }

    private fun applyAllowFlight(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        val allow = config.values["value"]?.toBooleanStrictOrNull()
            ?: config.values["allow"]?.toBooleanStrictOrNull()
            ?: true
        val snapshot = PlayerStateSnapshot(
            mapOf(
                "allowFlight" to player.allowFlight.toString(),
                "flying" to player.isFlying.toString(),
            ),
        )
        player.allowFlight = allow
        if (!allow && player.isFlying) {
            player.isFlying = false
        }
        track(
            EffectLease(
                UUID.randomUUID(),
                player.uniqueId,
                region.id,
                "allow_flight",
                config.scope,
                System.currentTimeMillis(),
                null,
                snapshot,
            ),
        )
        return ServiceResult.ok()
    }

    private fun applyWalkSpeed(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        val value = config.values["value"]?.toFloatOrNull()
            ?: return ServiceResult.fail("walk_speed effect requires value.")
        val snapshot = PlayerStateSnapshot(mapOf("walkSpeed" to player.walkSpeed.toString()))
        player.walkSpeed = value.coerceIn(-1.0f, 1.0f)
        track(EffectLease(UUID.randomUUID(), player.uniqueId, region.id, "walk_speed", config.scope, System.currentTimeMillis(), null, snapshot))
        return ServiceResult.ok()
    }

    private fun applyFlySpeed(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        val value = config.values["value"]?.toFloatOrNull()
            ?: return ServiceResult.fail("fly_speed effect requires value.")
        val snapshot = PlayerStateSnapshot(mapOf("flySpeed" to player.flySpeed.toString()))
        player.flySpeed = value.coerceIn(-1.0f, 1.0f)
        track(EffectLease(UUID.randomUUID(), player.uniqueId, region.id, "fly_speed", config.scope, System.currentTimeMillis(), null, snapshot))
        return ServiceResult.ok()
    }

    private fun applyInvisibilitySuppression(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        val previous = player.getPotionEffect(PotionEffectType.INVISIBILITY)
        player.removePotionEffect(PotionEffectType.INVISIBILITY)
        warnExternalVanish(player)
        val snapshot = LinkedHashMap<String, String>()
        if (previous != null) {
            snapshot["had"] = "true"
            snapshot["duration"] = previous.duration.toString()
            snapshot["amplifier"] = previous.amplifier.toString()
            snapshot["ambient"] = previous.isAmbient.toString()
            snapshot["particles"] = previous.hasParticles().toString()
            snapshot["icon"] = previous.hasIcon().toString()
        } else {
            snapshot["had"] = "false"
        }
        track(
            EffectLease(
                UUID.randomUUID(),
                player.uniqueId,
                region.id,
                "invisibility_suppression",
                config.scope,
                System.currentTimeMillis(),
                null,
                PlayerStateSnapshot(snapshot),
            ),
        )
        return ServiceResult.ok()
    }

    private fun restore(player: Player, lease: EffectLease): ServiceResult =
        when (lease.effectType.lowercase(Locale.ROOT)) {
            "scale" -> restoreScale(player, lease)
            "potion" -> restorePotion(player, lease)
            "allow_flight" -> restoreAllowFlight(player, lease)
            "walk_speed" -> restoreWalkSpeed(player, lease)
            "fly_speed" -> restoreFlySpeed(player, lease)
            "invisibility_suppression" -> restoreInvisibilitySuppression(player, lease)
            else -> ServiceResult.ok()
        }

    private fun restoreScale(player: Player, lease: EffectLease): ServiceResult {
        val previous = lease.snapshot.values["base"]?.toDoubleOrNull() ?: return ServiceResult.ok()
        val instance = scaleAttributeInstance(player) ?: return ServiceResult.ok()
        instance.baseValue = previous
        return ServiceResult.ok()
    }

    private fun restorePotion(player: Player, lease: EffectLease): ServiceResult {
        val typeName = lease.snapshot.values["effect"] ?: return ServiceResult.ok()
        val type = PotionEffectType.getByName(typeName) ?: return ServiceResult.ok()
        player.removePotionEffect(type)
        if (lease.snapshot.values["had"] == "true") {
            val duration = lease.snapshot.values["duration"]?.toIntOrNull() ?: return ServiceResult.ok()
            val amplifier = lease.snapshot.values["amplifier"]?.toIntOrNull() ?: 0
            val ambient = lease.snapshot.values["ambient"]?.toBooleanStrictOrNull() ?: false
            val particles = lease.snapshot.values["particles"]?.toBooleanStrictOrNull() ?: true
            val icon = lease.snapshot.values["icon"]?.toBooleanStrictOrNull() ?: true
            player.addPotionEffect(PotionEffect(type, duration, amplifier, ambient, particles, icon))
        }
        return ServiceResult.ok()
    }

    private fun restoreAllowFlight(player: Player, lease: EffectLease): ServiceResult {
        val allowFlight = lease.snapshot.values["allowFlight"]?.toBooleanStrictOrNull() ?: return ServiceResult.ok()
        val flying = lease.snapshot.values["flying"]?.toBooleanStrictOrNull() ?: false
        player.allowFlight = allowFlight
        player.isFlying = allowFlight && flying
        return ServiceResult.ok()
    }

    private fun restoreWalkSpeed(player: Player, lease: EffectLease): ServiceResult {
        val previous = lease.snapshot.values["walkSpeed"]?.toFloatOrNull() ?: return ServiceResult.ok()
        player.walkSpeed = previous.coerceIn(-1.0f, 1.0f)
        return ServiceResult.ok()
    }

    private fun restoreFlySpeed(player: Player, lease: EffectLease): ServiceResult {
        val previous = lease.snapshot.values["flySpeed"]?.toFloatOrNull() ?: return ServiceResult.ok()
        player.flySpeed = previous.coerceIn(-1.0f, 1.0f)
        return ServiceResult.ok()
    }

    private fun restoreInvisibilitySuppression(player: Player, lease: EffectLease): ServiceResult {
        if (lease.snapshot.values["had"] == "true") {
            val duration = lease.snapshot.values["duration"]?.toIntOrNull() ?: return ServiceResult.ok()
            val amplifier = lease.snapshot.values["amplifier"]?.toIntOrNull() ?: 0
            val ambient = lease.snapshot.values["ambient"]?.toBooleanStrictOrNull() ?: false
            val particles = lease.snapshot.values["particles"]?.toBooleanStrictOrNull() ?: true
            val icon = lease.snapshot.values["icon"]?.toBooleanStrictOrNull() ?: true
            player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, duration, amplifier, ambient, particles, icon))
        }
        return ServiceResult.ok()
    }

    private fun refreshPotion(player: Player, lease: EffectLease) {
        val type = PotionEffectType.getByName(lease.metadata["effect"] ?: return) ?: return
        val duration = lease.metadata["duration"]?.toIntOrNull() ?: plugin.config.getInt("effects.default-duration-ticks", 400)
        val amplifier = lease.metadata["amplifier"]?.toIntOrNull() ?: 0
        val ambient = lease.metadata["ambient"]?.toBooleanStrictOrNull() ?: false
        val particles = lease.metadata["particles"]?.toBooleanStrictOrNull() ?: true
        val icon = lease.metadata["icon"]?.toBooleanStrictOrNull() ?: true
        player.addPotionEffect(PotionEffect(type, duration, amplifier, ambient, particles, icon))
    }

    private fun scaleAttributeInstance(player: Player) =
        try {
            val attribute = Attribute.valueOf("SCALE")
            player.getAttribute(attribute)
        } catch (ex: IllegalArgumentException) {
            null
        }

    private fun warnExternalVanish(player: Player) {
        val keys = plugin.config.getStringList("vanish.metadata-keys")
        if (keys.any { key -> player.hasMetadata(key) }) {
            plugin.logger.fine("Player ${player.name} has external vanish metadata; Regions only suppresses vanilla invisibility by default.")
        }
    }
}
