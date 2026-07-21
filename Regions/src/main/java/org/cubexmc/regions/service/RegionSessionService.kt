package org.cubexmc.regions.service

import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RegionSessionService(
    private val plugin: RegionsPlugin,
    private val effects: ScopedEffectService,
) {
    private val sessionsByPlayer: ConcurrentHashMap<UUID, MutableList<RegionSession>> = ConcurrentHashMap()
    private val effectSignatures: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()

    fun enter(player: Player, region: RegionDefinition, modeActive: Boolean = true): RegionSession {
        val sessions = sessionsByPlayer.computeIfAbsent(player.uniqueId) { java.util.Collections.synchronizedList(ArrayList()) }
        var created = false
        val session = synchronized(sessions) {
            val existing = sessions.firstOrNull { it.regionId == region.id }
            if (existing != null) {
                return@synchronized existing
            }
            val newSession = RegionSession(UUID.randomUUID(), player.uniqueId, region.id, System.currentTimeMillis())
            newSession.metadata[MODE_ACTIVE_METADATA] = modeActive.toString()
            sessions.add(newSession)
            created = true
            newSession
        }
        if (!created) {
            return session
        }
        if (modeActive) activateMode(player, region)
        return session
    }

    fun reconcileEffects(player: Player, activeRegions: Collection<RegionDefinition>) {
        val resolution = plugin.overlaps().resolve(activeRegions)
        val signature = resolution.effects.joinToString("|") { it.identity }
        if (effectSignatures[player.uniqueId] == signature) return
        effects.cleanupDeclaredEffects(player, "overlap-resolution-changed")
        val byId = activeRegions.associateBy { it.id }
        var appliedAll = true
        for (resolved in resolution.effects) {
            val region = byId[resolved.sourceRegionId] ?: continue
            if (!effects.applyDeclared(player, region, resolved.config).success) {
                appliedAll = false
                break
            }
        }
        if (!appliedAll) {
            effects.cleanupDeclaredEffects(player, "overlap-apply-failed")
            effectSignatures.remove(player.uniqueId)
            plugin.logger.warning(
                "Unable to apply the complete resolved effect set for ${player.name}; " +
                    "declared region effects were rolled back",
            )
            return
        }
        if (signature.isEmpty()) effectSignatures.remove(player.uniqueId)
        else effectSignatures[player.uniqueId] = signature
    }

    fun reconcileModeOwner(player: Player, activeRegions: Collection<RegionDefinition>, primaryModeRegionId: String?) {
        val byId = activeRegions.associateBy { it.id }
        val sessions = activeSessions(player.uniqueId)
        for (session in sessions) {
            val region = byId[session.regionId] ?: continue
            val active = session.metadata[MODE_ACTIVE_METADATA]?.toBooleanStrictOrNull() ?: true
            if (active && session.regionId != primaryModeRegionId) {
                deactivateMode(player, region.id, "overlap-primary-changed")
                session.metadata[MODE_ACTIVE_METADATA] = false.toString()
            }
        }
        val primary = primaryModeRegionId?.let { byId[it] } ?: return
        val session = activeSession(player.uniqueId, primary.id) ?: return
        val active = session.metadata[MODE_ACTIVE_METADATA]?.toBooleanStrictOrNull() ?: false
        if (!active) {
            activateMode(player, primary)
            session.metadata[MODE_ACTIVE_METADATA] = true.toString()
        }
    }

    fun leave(player: Player, regionId: String, reason: String): Int {
        val sessions = sessionsByPlayer[player.uniqueId] ?: return 0
        val removed = synchronized(sessions) {
            val matches = sessions.filter { it.regionId == regionId }
            sessions.removeIf { it.regionId == regionId }
            if (sessions.isEmpty()) {
                sessionsByPlayer.remove(player.uniqueId)
            }
            matches
        }
        if (removed.any { it.metadata[MODE_ACTIVE_METADATA] != false.toString() }) {
            deactivateMode(player, regionId, reason)
        }
        val leaseCount = effects.cleanupRegion(player, regionId, reason)
        return removed.size + leaseCount
    }

    fun cleanup(player: Player, reason: String): Int {
        val removed = sessionsByPlayer.remove(player.uniqueId)?.let { sessions -> synchronized(sessions) { sessions.toList() } } ?: emptyList()
        for (session in removed) {
            if (session.metadata[MODE_ACTIVE_METADATA] != false.toString()) {
                deactivateMode(player, session.regionId, reason)
            }
        }
        val sessionCount = removed.size
        effectSignatures.remove(player.uniqueId)
        val leaseCount = effects.cleanupPlayer(player, reason)
        return sessionCount + leaseCount
    }

    fun cleanupAll(reason: String, shuttingDown: Boolean = false): Int {
        val online = plugin.server.onlinePlayers.toList()
        var count = online.sumOf { activeSessions(it.uniqueId).size }
        for (player in online) {
            if (!plugin.regionScheduler().isFolia) {
                cleanup(player, reason)
            } else if (!shuttingDown) {
                plugin.regionScheduler().runAtEntity(player, Runnable {
                    cleanup(player, reason)
                })
            }
        }
        val onlineIds = online.map { it.uniqueId }.toSet()
        val offline = sessionsByPlayer.keys.filter { !onlineIds.contains(it) }
        for (playerId in offline) {
            count += sessionsByPlayer.remove(playerId)?.let { sessions -> synchronized(sessions) { sessions.size } } ?: 0
            effectSignatures.remove(playerId)
        }
        if (shuttingDown && plugin.regionScheduler().isFolia) {
            sessionsByPlayer.clear()
            effectSignatures.clear()
        }
        return count
    }

    fun cleanupRegionAll(regionId: String, reason: String): Int {
        val online = plugin.server.onlinePlayers.associateBy { it.uniqueId }
        var count = 0
        for ((playerId, sessions) in sessionsByPlayer.entries.toList()) {
            val hasRegion = synchronized(sessions) { sessions.any { it.regionId == regionId } }
            if (!hasRegion) continue
            val player = online[playerId]
            if (player != null) {
                count += synchronized(sessions) { sessions.count { it.regionId == regionId } }
                plugin.regionScheduler().runAtEntity(player, Runnable {
                    leave(player, regionId, reason)
                })
            } else {
                count += synchronized(sessions) {
                    val before = sessions.size
                    sessions.removeIf { it.regionId == regionId }
                    before - sessions.size
                }
                if (sessions.isEmpty()) sessionsByPlayer.remove(playerId, sessions)
                effectSignatures.remove(playerId)
            }
        }
        return count
    }

    fun activeSessions(playerId: UUID): List<RegionSession> =
        sessionsByPlayer[playerId]?.let { sessions -> synchronized(sessions) { sessions.toList() } } ?: emptyList()

    fun activeSession(playerId: UUID, regionId: String): RegionSession? =
        sessionsByPlayer[playerId]?.let { sessions -> synchronized(sessions) { sessions.firstOrNull { it.regionId == regionId } } }

    fun setMetadata(player: Player, regionId: String, key: String, value: String): Boolean {
        val session = activeSession(player.uniqueId, regionId) ?: return false
        session.metadata[key] = value
        return true
    }

    fun clearMetadata(player: Player, regionId: String, key: String): Boolean {
        val session = activeSession(player.uniqueId, regionId) ?: return false
        return session.metadata.remove(key) != null
    }

    fun refreshPlayer(player: Player) {
        effects.refreshPlayer(player)
    }

    fun watchdog() {
        val online = plugin.server.onlinePlayers.map { it.uniqueId }.toSet()
        val offline = sessionsByPlayer.keys.filter { !online.contains(it) }
        for (playerId in offline) {
            sessionsByPlayer.remove(playerId)
            effectSignatures.remove(playerId)
        }
        if (offline.isNotEmpty()) {
            plugin.logger.fine("Regions watchdog cleared ${offline.size} offline player session bucket(s).")
        }
    }

    private fun activateMode(player: Player, region: RegionDefinition) {
        plugin.combatModes().onEnter(player, region)
        plugin.raceModes().onEnter(player, region)
        plugin.roundModes().onEnter(player, region)
    }

    private fun deactivateMode(player: Player, regionId: String, reason: String) {
        plugin.combatModes().onLeave(player, regionId, reason)
        plugin.raceModes().onLeave(player, regionId, reason)
        plugin.roundModes().onLeave(player, regionId, reason)
    }

    private companion object {
        const val MODE_ACTIVE_METADATA = "overlap-mode-active"
    }
}
