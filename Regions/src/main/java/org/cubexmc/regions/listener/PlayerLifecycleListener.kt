package org.cubexmc.regions.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionTrigger

class PlayerLifecycleListener(private val plugin: RegionsPlugin) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.combatModes().restoreIfPending(event.player, "join-recovery")
        plugin.roundModes().restoreIfPending(event.player, "join-recovery")
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.world == to.world && from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return
        }
        plugin.detection().updatePlayer(event.player)
        plugin.raceModes().onMove(event.player)
        plugin.roundModes().onMove(event)
    }

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        plugin.regionScheduler().runAtEntityLater(event.player, Runnable {
            plugin.detection().updatePlayer(event.player)
        }, 1L)
    }

    @EventHandler(ignoreCancelled = true)
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (plugin.flagRules().isDenied(event.player, "fly")) {
            event.isCancelled = true
            event.player.isFlying = false
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = attackingPlayer(event) ?: return
        if (plugin.roundModes().onDamage(event)) {
            return
        }
        if (plugin.flagRules().isDenied(victim, "pvp") || plugin.flagRules().isDenied(attacker, "pvp")) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (plugin.flagRules().isDenied(event.player, "item_drop")) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (plugin.flagRules().isDenied(player, "item_pickup")) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val label = event.message.removePrefix("/").trim().substringBefore(' ')
        for (session in plugin.sessions().activeSessions(event.player.uniqueId)) {
            val region = plugin.regions().find(session.regionId) ?: continue
            session.metadata["last_command"] = label
            plugin.triggers().fire(RegionTrigger.ON_COMMAND, event.player, region)
        }
        if (plugin.flagRules().isCommandBlocked(event.player, label)) {
            event.isCancelled = true
            plugin.lang().sendRaw(event.player, "§c这个区域不允许使用 /$label。")
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (plugin.config.getBoolean("safety.cleanup-on-quit", true)) {
            plugin.sessions().cleanup(event.player, "quit")
        }
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        if (plugin.config.getBoolean("safety.cleanup-on-quit", true)) {
            plugin.sessions().cleanup(event.player, "kick")
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val combatHandled = plugin.combatModes().onDeath(event)
        val roundHandled = plugin.roundModes().onDeath(event)
        for (session in plugin.sessions().activeSessions(event.entity.uniqueId)) {
            val region = plugin.regions().find(session.regionId) ?: continue
            plugin.triggers().fire(RegionTrigger.ON_DEATH, event.entity, region)
        }
        val killer = event.entity.killer
        if (killer != null) {
            for (session in plugin.sessions().activeSessions(killer.uniqueId)) {
                val region = plugin.regions().find(session.regionId) ?: continue
                plugin.triggers().fire(RegionTrigger.ON_KILL, killer, region)
            }
        }
        if (!combatHandled && !roundHandled && plugin.config.getBoolean("safety.cleanup-on-death", true)) {
            plugin.sessions().cleanup(event.entity, "death")
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.combatModes().onRespawn(event)
        plugin.roundModes().onRespawn(event.player)
        plugin.regionScheduler().runAtEntityLater(event.player, Runnable {
            for (session in plugin.sessions().activeSessions(event.player.uniqueId)) {
                val region = plugin.regions().find(session.regionId) ?: continue
                plugin.triggers().fire(RegionTrigger.ON_RESPAWN, event.player, region)
            }
        }, 1L)
    }

    private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? {
        val direct = event.damager
        if (direct is Player) {
            return direct
        }
        if (direct is Projectile) {
            return direct.shooter as? Player
        }
        return null
    }
}
