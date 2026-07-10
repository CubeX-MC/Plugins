package org.cubexmc.regions.service

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RegionSessionService(
    private val plugin: RegionsPlugin,
    private val effects: ScopedEffectService,
) {
    private val sessionsByPlayer: ConcurrentHashMap<UUID, MutableList<RegionSession>> = ConcurrentHashMap()

    fun enter(player: Player, region: RegionDefinition): RegionSession {
        val sessions = sessionsByPlayer.computeIfAbsent(player.uniqueId) { java.util.Collections.synchronizedList(ArrayList()) }
        var created = false
        val session = synchronized(sessions) {
            val existing = sessions.firstOrNull { it.regionId == region.id }
            if (existing != null) {
                return@synchronized existing
            }
            val newSession = RegionSession(UUID.randomUUID(), player.uniqueId, region.id, System.currentTimeMillis())
            sessions.add(newSession)
            created = true
            newSession
        }
        if (!created) {
            return session
        }
        for (effect in region.effects) {
            effects.apply(player, region, effect)
        }
        if (region.flags["vanish"]?.value.equals("deny", ignoreCase = true)) {
            effects.apply(player, region, EffectConfig("invisibility_suppression", EffectScope.WHILE_INSIDE))
        }
        plugin.combatModes().onEnter(player, region)
        plugin.raceModes().onEnter(player, region)
        plugin.roundModes().onEnter(player, region)
        return session
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
        plugin.combatModes().onLeave(player, regionId, reason)
        plugin.raceModes().onLeave(player, regionId, reason)
        plugin.roundModes().onLeave(player, regionId, reason)
        val leaseCount = effects.cleanupRegion(player, regionId, reason)
        return removed.size + leaseCount
    }

    fun cleanup(player: Player, reason: String): Int {
        val removed = sessionsByPlayer.remove(player.uniqueId)?.let { sessions -> synchronized(sessions) { sessions.toList() } } ?: emptyList()
        for (session in removed) {
            plugin.combatModes().onLeave(player, session.regionId, reason)
            plugin.raceModes().onLeave(player, session.regionId, reason)
            plugin.roundModes().onLeave(player, session.regionId, reason)
        }
        val sessionCount = removed.size
        val leaseCount = effects.cleanupPlayer(player, reason)
        return sessionCount + leaseCount
    }

    fun cleanupAll(reason: String): Int {
        val online = Bukkit.getOnlinePlayers().toList()
        var count = online.sumOf { activeSessions(it.uniqueId).size }
        for (player in online) {
            plugin.regionScheduler().runAtEntity(player, Runnable {
                cleanup(player, reason)
            })
        }
        val onlineIds = online.map { it.uniqueId }.toSet()
        val offline = sessionsByPlayer.keys.filter { !onlineIds.contains(it) }
        for (playerId in offline) {
            count += sessionsByPlayer.remove(playerId)?.let { sessions -> synchronized(sessions) { sessions.size } } ?: 0
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
        val online = Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
        val offline = sessionsByPlayer.keys.filter { !online.contains(it) }
        for (playerId in offline) {
            sessionsByPlayer.remove(playerId)
        }
        if (offline.isNotEmpty()) {
            plugin.logger.fine("Regions watchdog cleared ${offline.size} offline player session bucket(s).")
        }
    }
}
