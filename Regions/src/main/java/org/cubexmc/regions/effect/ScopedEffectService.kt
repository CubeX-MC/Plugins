package org.cubexmc.regions.effect

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectLease
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.PlayerStateSnapshot
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.service.ServiceResult
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class ScopedEffectService(private val plugin: RegionsPlugin) {
    private val effectTypes: MutableSet<String> = LinkedHashSet()
    private val leases: MutableMap<UUID, EffectLease> = ConcurrentHashMap()
    private val applicationSequence = AtomicLong()
    private val leaseStore: EffectLeaseStore? = runCatching { plugin.dataFolder }
        .getOrNull()
        ?.let { EffectLeaseStore(it, plugin.logger) }

    init {
        val persisted = leaseStore?.load().orEmpty()
        persisted.forEach { leases[it.id] = it }
        applicationSequence.set(persisted.maxOfOrNull { it.applicationOrder } ?: 0L)
    }

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
            "invisibility_suppression",
        ).forEach { register(it) }
    }

    fun isRegistered(type: String): Boolean = effectTypes.contains(type.lowercase())

    fun allTypes(): Set<String> = effectTypes.toSet()

    fun apply(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        return applyInternal(player, region, config, emptyMap())
    }

    fun applyDeclared(player: Player, region: RegionDefinition, config: EffectConfig): ServiceResult {
        return applyInternal(player, region, config, mapOf(ORIGIN_KEY to DECLARED_ORIGIN))
    }

    private fun applyInternal(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
        if (!isRegistered(config.type)) {
            return ServiceResult.fail("Unknown effect type ${config.type}")
        }
        val result = when (config.type.lowercase(Locale.ROOT)) {
            "scale" -> applyScale(player, region, config, leaseMetadata)
            "potion" -> applyPotion(player, region, config, leaseMetadata)
            "allow_flight" -> applyAllowFlight(player, region, config, leaseMetadata)
            "walk_speed" -> applyWalkSpeed(player, region, config, leaseMetadata)
            "fly_speed" -> applyFlySpeed(player, region, config, leaseMetadata)
            "glowing" -> applyGlowing(player, region, config, leaseMetadata)
            "invisibility_suppression" -> applyInvisibilitySuppression(player, region, config, leaseMetadata)
            else -> ServiceResult.fail("Effect ${config.type} is registered but not implemented yet.")
        }
        if (!result.success) {
            plugin.logger.warning("Failed to apply effect ${config.type} to ${player.name} in ${region.id}: ${result.reason}")
        }
        return result
    }

    fun restoreLease(leaseId: UUID): ServiceResult {
        val lease = leases[leaseId] ?: return ServiceResult.ok()
        val player = plugin.server.getPlayer(lease.playerId) ?: return ServiceResult.ok()
        return restoreKnownPlayer(player, leaseId)
    }

    fun restoreIfPending(player: Player, reason: String): Int {
        val count = cleanupPlayer(player, reason)
        if (count > 0) {
            plugin.logger.warning("Restored $count persisted Regions effect lease(s) for ${player.name}: $reason")
        }
        return count
    }

    internal fun pendingLeaseCount(playerId: UUID? = null): Int =
        if (playerId == null) leases.size else leases.values.count { it.playerId == playerId }

    fun cleanupPlayer(player: Player, reason: String): Int {
        val playerLeases = leases.values
            .filter { it.playerId == player.uniqueId }
            .sortedByDescending { it.applicationOrder }
        val restored = playerLeases.count { restoreKnownPlayer(player, it.id).success }
        if (restored > 0) {
            plugin.logger.fine("Cleaned $restored effect lease(s) for ${player.name}: $reason")
        }
        return restored
    }

    fun cleanupRegion(player: Player, regionId: String, reason: String): Int {
        val playerLeases = leases.values
            .filter { it.playerId == player.uniqueId && it.regionId == regionId }
            .sortedByDescending { it.applicationOrder }
        val restored = playerLeases.count { restoreKnownPlayer(player, it.id).success }
        if (restored > 0) {
            plugin.logger.fine("Cleaned $restored effect lease(s) for ${player.name} in $regionId: $reason")
        }
        return restored
    }

    fun cleanupModeEffects(player: Player, regionId: String, reason: String): Int {
        val modeLeases = leases.values
            .filter {
                it.playerId == player.uniqueId &&
                    it.regionId == regionId &&
                    it.scope == EffectScope.UNTIL_MODE_END
            }
            .sortedByDescending { it.applicationOrder }
        val restored = modeLeases.count { restoreKnownPlayer(player, it.id).success }
        if (restored > 0) {
            plugin.logger.fine("Cleaned $restored mode effect lease(s) for ${player.name} in $regionId: $reason")
        }
        return restored
    }

    fun cleanupDeclaredEffects(player: Player, reason: String): Int {
        val declared = leases.values
            .filter { it.playerId == player.uniqueId && it.metadata[ORIGIN_KEY] == DECLARED_ORIGIN }
            .sortedByDescending { it.applicationOrder }
        val restored = declared.count { restoreKnownPlayer(player, it.id).success }
        if (restored > 0) {
            plugin.logger.fine("Reconciled $restored declared effect lease(s) for ${player.name}: $reason")
        }
        return restored
    }

    fun refreshAll() {
        val now = System.currentTimeMillis()
        for (lease in leases.values.toList()) {
            val player = plugin.server.getPlayer(lease.playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                refreshLease(player, lease, now)
            })
        }
    }

    fun refreshPlayer(player: Player, nowMillis: Long = System.currentTimeMillis()) {
        for (lease in leases.values.toList()) {
            if (lease.playerId == player.uniqueId) {
                refreshLease(player, lease, nowMillis)
            }
        }
    }

    private fun refreshLease(player: Player, lease: EffectLease, nowMillis: Long) {
        if (lease.expiresAtMillis?.let { nowMillis >= it } == true) {
            restoreKnownPlayer(player, lease.id)
            return
        }
        if (lease.effectType.equals("potion", ignoreCase = true)) {
            refreshPotion(player, lease)
        } else if (lease.effectType.equals("invisibility_suppression", ignoreCase = true)) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY)
        }
    }

    private fun restoreKnownPlayer(player: Player, leaseId: UUID): ServiceResult {
        val lease = leases[leaseId] ?: return ServiceResult.ok()
        if (lease.playerId != player.uniqueId) {
            return ServiceResult.fail("Effect lease player mismatch.")
        }
        val restored = runCatching { restore(player, lease) }
            .getOrElse { error ->
                ServiceResult.fail("Unable to restore ${lease.effectType}: ${error.message}")
            }
        if (!restored.success) return restored
        if (!leases.remove(lease.id, lease)) return ServiceResult.ok()
        if (persistLeases()) return ServiceResult.ok()
        leases[lease.id] = lease
        return ServiceResult.fail("Effect was restored, but its escrow record could not be removed.")
    }

    private fun applyScale(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
        val value = config.values["value"]?.toDoubleOrNull()
            ?: return ServiceResult.fail("scale effect requires value.")
        val minScale = plugin.config.getDouble("effects.scale.min", 0.1)
        val maxScale = plugin.config.getDouble("effects.scale.max", 4.0)
        val safeValue = min(max(value, minScale), maxScale)
        val instance = scaleAttributeInstance(player)
            ?: return ServiceResult.fail("Current server does not expose the scale attribute.")
        val previous = instance.baseValue
        instance.baseValue = safeValue
        return trackApplied(player, createLease(
            player,
            region,
            config,
            "scale",
            PlayerStateSnapshot(mapOf("base" to previous.toString())),
            leaseMetadata,
        ))
    }

    private fun applyPotion(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
        val name = config.values["effect"] ?: config.values["name"]
            ?: return ServiceResult.fail("potion effect requires effect.")
        val type = resolvePotionEffect(name)
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
        snapshot["effect"] = type.key.toString()
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
        return trackApplied(player, createLease(
            player,
            region,
            config,
            "potion",
            PlayerStateSnapshot(snapshot),
            leaseMetadata + mapOf(
                "effect" to type.key.toString(),
                "duration" to duration.toString(),
                "amplifier" to amplifier.toString(),
                "ambient" to ambient.toString(),
                "particles" to particles.toString(),
                "icon" to icon.toString(),
            ),
        ))
    }

    private fun applyAllowFlight(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
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
        return trackApplied(player, createLease(player, region, config, "allow_flight", snapshot, leaseMetadata))
    }

    private fun applyWalkSpeed(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
        val value = config.values["value"]?.toFloatOrNull()
            ?: return ServiceResult.fail("walk_speed effect requires value.")
        val snapshot = PlayerStateSnapshot(mapOf("walkSpeed" to player.walkSpeed.toString()))
        player.walkSpeed = value.coerceIn(-1.0f, 1.0f)
        return trackApplied(player, createLease(player, region, config, "walk_speed", snapshot, leaseMetadata))
    }

    private fun applyFlySpeed(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
        val value = config.values["value"]?.toFloatOrNull()
            ?: return ServiceResult.fail("fly_speed effect requires value.")
        val snapshot = PlayerStateSnapshot(mapOf("flySpeed" to player.flySpeed.toString()))
        player.flySpeed = value.coerceIn(-1.0f, 1.0f)
        return trackApplied(player, createLease(player, region, config, "fly_speed", snapshot, leaseMetadata))
    }

    private fun applyGlowing(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
        val value = config.values["value"]?.toBooleanStrictOrNull() ?: true
        val snapshot = PlayerStateSnapshot(mapOf("glowing" to player.isGlowing.toString()))
        player.isGlowing = value
        return trackApplied(player, createLease(player, region, config, "glowing", snapshot, leaseMetadata))
    }

    private fun applyInvisibilitySuppression(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        leaseMetadata: Map<String, String>,
    ): ServiceResult {
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
        return trackApplied(player, createLease(
            player,
            region,
            config,
            "invisibility_suppression",
            PlayerStateSnapshot(snapshot),
            leaseMetadata,
        ))
    }

    private fun trackApplied(player: Player, lease: EffectLease): ServiceResult {
        leases[lease.id] = lease
        if (persistLeases()) return ServiceResult.ok()
        leases.remove(lease.id)
        val rollback = runCatching { restore(player, lease) }
        return if (rollback.isSuccess) {
            ServiceResult.fail("Unable to persist effect escrow; the effect was rolled back.")
        } else {
            ServiceResult.fail("Unable to persist effect escrow and rollback failed: ${rollback.exceptionOrNull()?.message}")
        }
    }

    private fun persistLeases(): Boolean = leaseStore?.replace(leases.values) ?: true

    private fun createLease(
        player: Player,
        region: RegionDefinition,
        config: EffectConfig,
        effectType: String,
        snapshot: PlayerStateSnapshot,
        metadata: Map<String, String> = emptyMap(),
    ): EffectLease {
        val now = System.currentTimeMillis()
        return EffectLease(
            id = UUID.randomUUID(),
            playerId = player.uniqueId,
            regionId = region.id,
            effectType = effectType,
            scope = config.scope,
            appliedAtMillis = now,
            applicationOrder = applicationSequence.incrementAndGet(),
            expiresAtMillis = expiryMillis(config, now),
            snapshot = snapshot,
            metadata = metadata,
        )
    }

    private fun expiryMillis(config: EffectConfig, now: Long): Long? {
        if (config.scope != EffectScope.TIMED) return null
        val durationTicks = config.values["lease-duration-ticks"]?.toLongOrNull()
            ?: config.values["duration-ticks"]?.toLongOrNull()
            ?: plugin.config.getLong("effects.default-duration-ticks", 400L)
        return now + durationTicks.coerceAtLeast(1L) * TICK_MILLIS
    }

    private fun restore(player: Player, lease: EffectLease): ServiceResult =
        when (lease.effectType.lowercase(Locale.ROOT)) {
            "scale" -> restoreScale(player, lease)
            "potion" -> restorePotion(player, lease)
            "allow_flight" -> restoreAllowFlight(player, lease)
            "walk_speed" -> restoreWalkSpeed(player, lease)
            "fly_speed" -> restoreFlySpeed(player, lease)
            "glowing" -> restoreGlowing(player, lease)
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
        val type = resolvePotionEffect(typeName) ?: return ServiceResult.ok()
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

    private fun restoreGlowing(player: Player, lease: EffectLease): ServiceResult {
        val previous = lease.snapshot.values["glowing"]?.toBooleanStrictOrNull() ?: return ServiceResult.ok()
        player.isGlowing = previous
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
        val type = resolvePotionEffect(lease.metadata["effect"] ?: return) ?: return
        val duration = lease.metadata["duration"]?.toIntOrNull() ?: plugin.config.getInt("effects.default-duration-ticks", 400)
        val amplifier = lease.metadata["amplifier"]?.toIntOrNull() ?: 0
        val ambient = lease.metadata["ambient"]?.toBooleanStrictOrNull() ?: false
        val particles = lease.metadata["particles"]?.toBooleanStrictOrNull() ?: true
        val icon = lease.metadata["icon"]?.toBooleanStrictOrNull() ?: true
        player.addPotionEffect(PotionEffect(type, duration, amplifier, ambient, particles, icon))
    }

    private fun scaleAttributeInstance(player: Player) = player.getAttribute(Attribute.SCALE)

    private fun resolvePotionEffect(value: String): PotionEffectType? {
        val normalized = value.lowercase(Locale.ROOT)
        val key = if (':' in normalized) {
            NamespacedKey.fromString(normalized)
        } else {
            NamespacedKey.minecraft(normalized)
        }
        return key?.let { Registry.MOB_EFFECT.get(it) }
    }

    private fun warnExternalVanish(player: Player) {
        val keys = plugin.config.getStringList("vanish.metadata-keys")
        if (keys.any { key -> player.hasMetadata(key) }) {
            plugin.logger.fine("Player ${player.name} has external vanish metadata; Regions only suppresses vanilla invisibility by default.")
        }
    }

    private companion object {
        const val TICK_MILLIS = 50L
        const val ORIGIN_KEY = "regions-origin"
        const val DECLARED_ORIGIN = "region-definition"
    }
}
