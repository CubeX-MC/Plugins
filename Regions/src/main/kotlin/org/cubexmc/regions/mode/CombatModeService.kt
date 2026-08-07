package org.cubexmc.regions.mode

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CombatModeService(private val plugin: RegionsPlugin) {
    private val states: ConcurrentHashMap<String, CombatState> = ConcurrentHashMap()
    private val endingRegions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingRespawnRestores: ConcurrentHashMap<UUID, GearSnapshot> = ConcurrentHashMap()
    private val gearStore = CombatGearStore(plugin)

    init {
        gearStore.load()
    }

    fun onEnter(player: Player, region: RegionDefinition) {
        if (!isCombatMode(region)) {
            return
        }
        if (endingRegions.contains(region.id)) {
            plugin.sendGame(player, plugin.gameText("game.combat.restoring", mapOf("name" to region.name)))
            return
        }
        val state = state(region)
        if (state.active) {
            plugin.sendGame(player, plugin.gameText("game.combat.in-progress", mapOf("name" to region.name)))
            return
        }
        val maxPlayers = maxPlayers(region)
        if (maxPlayers > 0 && !state.players.contains(player.uniqueId) && state.players.size >= maxPlayers) {
            plugin.sendGame(player, plugin.gameText("game.combat.full", mapOf("name" to region.name)))
            return
        }
        state.players.add(player.uniqueId)
        state.ready.remove(player.uniqueId)
        if (!state.active && canPromptReady(region, state)) {
            if (requireReady(region)) {
                promptReady(region, state)
            } else {
                start(region, state)
            }
        }
    }

    fun onLeave(player: Player, regionId: String, reason: String) {
        val state = states[regionId] ?: return
        state.players.remove(player.uniqueId)
        state.ready.remove(player.uniqueId)
        restoreGear(player, state, teleportOut = true, reason = reason)
        if (state.active) {
            maybeEndAfterRosterChange(state, "players-left")
        } else if (plugin.regions().find(regionId)?.let { !canPromptReady(it, state) } == true) {
            state.prompted = false
        }
        if (state.players.isEmpty() && state.gear.isEmpty()) {
            states.remove(regionId)
        }
    }

    fun ready(player: Player, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isCombatMode(region)) {
            plugin.sendGame(player, plugin.gameText("game.combat.not-combat"))
            return true
        }
        val state = state(region)
        if (!state.players.contains(player.uniqueId)) {
            plugin.sendGame(player, plugin.gameText("game.combat.not-inside"))
            return true
        }
        if (state.active) {
            plugin.sendGame(player, plugin.gameText("game.combat.already-started"))
            return true
        }
        state.ready.add(player.uniqueId)
        broadcast(state, plugin.gameText("game.combat.ready", mapOf("player" to player.name, "current" to state.ready.size.toString(), "total" to state.players.size.toString())))
        if (!canPromptReady(region, state)) {
            plugin.sendGame(player, startRequirementMessage(region, state))
            return true
        }
        if (state.ready.containsAll(state.players)) {
            start(region, state)
        }
        return true
    }

    fun forceEnd(regionId: String, reason: String): Boolean {
        if (!states.containsKey(regionId)) {
            return false
        }
        end(regionId, reason)
        return true
    }

    fun onDeath(event: PlayerDeathEvent): Boolean {
        val player = event.entity
        val state = states.values.firstOrNull { it.players.contains(player.uniqueId) && it.active } ?: return false
        val snapshot = state.gear.remove(player.uniqueId)
        if (snapshot != null) {
            pendingRespawnRestores[player.uniqueId] = snapshot
            event.drops.clear()
            event.droppedExp = 0
        }
        state.players.remove(player.uniqueId)
        state.ready.remove(player.uniqueId)
        plugin.sendGame(player, plugin.gameText("game.combat.removed"))
        maybeEndAfterRosterChange(state, "death")
        return true
    }

    fun onRespawn(event: PlayerRespawnEvent) {
        val snapshot = pendingRespawnRestores.remove(event.player.uniqueId) ?: return
        restoreSnapshot(event.player, snapshot)
        snapshot.respawn?.let { event.respawnLocation = it }
    }

    fun cleanupAll(reason: String, shuttingDown: Boolean = false) {
        val immediate = !plugin.regionScheduler().isFolia
        for (regionId in states.keys.toList()) {
            end(regionId, reason, immediate = immediate, restorePlayers = !shuttingDown || immediate)
        }
        for ((playerId, snapshot) in pendingRespawnRestores.toMap()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            val restore = Runnable {
                restoreSnapshot(player, snapshot)
                pendingRespawnRestores.remove(playerId)
            }
            if (immediate) restore.run()
            else if (!shuttingDown) plugin.regionScheduler().runAtEntity(player, restore)
        }
        for (playerId in gearStore.allPlayerIds()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            val restore = Runnable {
                restoreStored(player, "cleanup-all:$reason")
            }
            if (immediate) restore.run()
            else if (!shuttingDown) plugin.regionScheduler().runAtEntity(player, restore)
        }
        if (shuttingDown) pendingRespawnRestores.clear()
    }

    fun restoreIfPending(player: Player, reason: String): Boolean =
        restoreStored(player, reason)

    fun status(regionId: String): String {
        val state = states[regionId] ?: return "idle"
        val region = plugin.regions().find(regionId)
        val unions = if (region?.mode?.type.equals("union_war", ignoreCase = true)) " unions=${unionIds(state).size}" else ""
        return if (state.active) "active players=${state.players.size}$unions" else "waiting ready=${state.ready.size}/${state.players.size}$unions"
    }

    private fun start(region: RegionDefinition, state: CombatState) {
        if (state.active) {
            return
        }
        state.active = true
        state.ready.clear()
        state.prompted = false
        val replaceGear = shouldReplaceGear(region)
        for (playerId in state.players.toList()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                if (states[region.id] !== state || !state.active || !state.players.contains(playerId)) {
                    return@Runnable
                }
                runCatching {
                    if (replaceGear && !state.gear.containsKey(playerId)) {
                        val snapshot = GearSnapshot.capture(player, outsideLocation(region))
                        val previous = state.gear.putIfAbsent(playerId, snapshot)
                        if (previous == null) {
                            gearStore.put(playerId, region.id, snapshot)
                            applyKit(player, region)
                        }
                    }
                    plugin.sendGame(player, plugin.gameText("game.combat.started"))
                    plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
                }.onFailure { error ->
                    plugin.log().severe("Failed to start combat ${region.id} for ${player.name}: ${error.message}")
                    end(region.id, "start-failed")
                }
            })
        }
    }

    private fun end(
        regionId: String,
        reason: String,
        immediate: Boolean = !plugin.regionScheduler().isFolia,
        restorePlayers: Boolean = true,
    ) {
        val state = states[regionId] ?: return
        state.active = false
        endingRegions.add(regionId)
        states.remove(regionId, state)
        val region = plugin.regions().find(regionId)
        plugin.audit().record(
            null,
            regionId,
            "mode.combat.end",
            reason,
            mapOf(
                "revision" to (region?.publishedRevision?.toString() ?: "unknown"),
                "participants" to state.players.size.toString(),
                "mode" to (region?.mode?.type ?: "unknown"),
            ),
        )
        if (restorePlayers) {
            val players = (state.players + state.gear.keys).toSet()
                .mapNotNull { plugin.server.getPlayer(it) }
            val remaining = AtomicInteger(players.size)
            if (players.isEmpty()) endingRegions.remove(regionId)
            for (player in players) {
                val restore = Runnable {
                    try {
                        if (region != null) {
                            plugin.triggers().fire(RegionTrigger.ON_MODE_END, player, region)
                        }
                        plugin.effects().cleanupModeEffects(player, regionId, "mode-end:$reason")
                        restoreGear(player, state, teleportOut = true, reason = reason)
                        plugin.sendGame(player, plugin.gameText("game.combat.ended"))
                    } finally {
                        if (remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                    }
                }
                runCatching {
                    if (immediate) restore.run() else plugin.regionScheduler().runAtEntity(player, restore)
                }.onFailure {
                    plugin.log().severe("Failed to schedule combat cleanup for ${player.name} in $regionId: ${it.message}")
                    if (!immediate && remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                }
            }
        } else {
            endingRegions.remove(regionId)
        }
    }

    private fun restoreGear(player: Player, state: CombatState, teleportOut: Boolean, reason: String) {
        val snapshot = state.gear.remove(player.uniqueId) ?: return
        restoreSnapshot(player, snapshot)
        if (teleportOut) {
            snapshot.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
        }
        plugin.log().debug("Restored combat gear for ${player.name} in ${state.regionId}: $reason")
    }

    private fun restoreSnapshot(player: Player, snapshot: GearSnapshot) {
        player.inventory.contents = snapshot.contents
        player.inventory.armorContents = snapshot.armor
        player.inventory.setItemInOffHand(snapshot.offhand)
        player.level = snapshot.level
        player.exp = snapshot.exp
        player.gameMode = snapshot.gameMode
        player.updateInventory()
        gearStore.take(player.uniqueId)
    }

    private fun restoreStored(player: Player, reason: String): Boolean {
        val stored = gearStore.take(player.uniqueId) ?: return false
        player.inventory.contents = stored.contents
        player.inventory.armorContents = stored.armor
        player.inventory.setItemInOffHand(stored.offhand)
        player.level = stored.level
        player.exp = stored.exp
        player.gameMode = stored.gameMode
        player.updateInventory()
        stored.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
        plugin.log().warn("Restored persisted combat escrow for ${player.name}: $reason")
        return true
    }

    private fun applyKit(player: Player, region: RegionDefinition) {
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(null)
        val kit = parseItems(region.mode?.values?.get("kit"))
        for (item in kit) {
            player.inventory.addItem(item)
        }
        val armor = parseItems(region.mode?.values?.get("armor")).take(4)
        val armorContents = arrayOfNulls<ItemStack>(4)
        for (index in armor.indices) {
            armorContents[index] = armor[index]
        }
        player.inventory.armorContents = armorContents
        parseItems(region.mode?.values?.get("offhand")).firstOrNull()?.let { player.inventory.setItemInOffHand(it) }
        player.updateInventory()
    }

    private fun parseItems(value: String?): List<ItemStack> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return value.split(',', ';')
            .mapNotNull { raw ->
                val parts = raw.trim().split(':')
                val material = Material.matchMaterial(parts[0].trim().uppercase()) ?: return@mapNotNull null
                val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                ItemStack(material, amount)
            }
    }

    private fun shouldReplaceGear(region: RegionDefinition): Boolean {
        val values = region.mode?.values ?: return false
        return values["replace-gear"]?.toBooleanStrictOrNull() == true ||
            !values["kit"].isNullOrBlank() ||
            !values["armor"].isNullOrBlank() ||
            !values["offhand"].isNullOrBlank()
    }

    private fun promptReady(region: RegionDefinition, state: CombatState) {
        if (state.prompted) {
            return
        }
        state.prompted = true
        broadcast(state, plugin.gameText("game.combat.ready-prompt", mapOf("name" to region.name, "id" to region.id)))
    }

    private fun canPromptReady(region: RegionDefinition, state: CombatState): Boolean {
        if (state.players.size < minPlayers(region)) {
            return false
        }
        if (!region.mode?.type.equals("union_war", ignoreCase = true)) {
            return true
        }
        return unionIds(state).size >= minUnions(region)
    }

    private fun startRequirementMessage(region: RegionDefinition, state: CombatState): String {
        if (state.players.size < minPlayers(region)) {
            return plugin.gameText("game.combat.waiting-players", mapOf("current" to state.players.size.toString(), "required" to minPlayers(region).toString()))
        }
        if (region.mode?.type.equals("union_war", ignoreCase = true)) {
            return plugin.gameText("game.combat.waiting-unions", mapOf("required" to minUnions(region).toString(), "current" to unionIds(state).size.toString()))
        }
        return plugin.gameText("game.combat.not-ready")
    }

    private fun maybeEndAfterRosterChange(state: CombatState, reason: String) {
        val region = plugin.regions().find(state.regionId) ?: run {
            end(state.regionId, reason)
            return
        }
        if (state.players.size < minPlayers(region)) {
            end(state.regionId, reason)
            return
        }
        if (region.mode?.type.equals("union_war", ignoreCase = true)) {
            val unions = unionIds(state)
            if (unions.size <= 1) {
                val winner = unions.firstOrNull()
                if (winner != null) {
                    broadcast(state, plugin.gameText("game.combat.union-winner", mapOf("union" to winner)))
                }
                end(state.regionId, reason)
            }
        }
    }

    private fun broadcast(state: CombatState, message: String) {
        for (playerId in state.players) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                plugin.sendGame(player, message)
            })
        }
    }

    private fun state(region: RegionDefinition): CombatState =
        states.computeIfAbsent(region.id) { CombatState(region.id) }

    private fun minPlayers(region: RegionDefinition?): Int =
        region?.mode?.values?.get("min-players")?.toIntOrNull()?.coerceAtLeast(1)
            ?: if (region?.mode?.type.equals("dual_pvp", ignoreCase = true)) 2 else 2

    private fun maxPlayers(region: RegionDefinition): Int =
        region.mode?.values?.get("max-players")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun minUnions(region: RegionDefinition): Int =
        region.mode?.values?.get("min-unions")?.toIntOrNull()?.coerceAtLeast(2) ?: 2

    private fun requireReady(region: RegionDefinition): Boolean =
        region.mode?.values?.get("require-ready")?.toBooleanStrictOrNull() ?: true

    private fun unionIds(state: CombatState): Set<String> {
        val provider = plugin.unions().active() ?: return emptySet()
        return state.players
            .mapNotNull { playerId -> provider.getUnion(playerId)?.id }
            .toSet()
    }

    private fun outsideLocation(region: RegionDefinition): Location? {
        val raw = region.mode?.values?.get("respawn") ?: region.mode?.values?.get("outside") ?: return null
        val parts = raw.split(',')
        if (parts.size < 4) {
            return null
        }
        val world = plugin.server.getWorld(parts[0].trim()) ?: return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0.0f
        val pitch = parts.getOrNull(5)?.trim()?.toFloatOrNull() ?: 0.0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun isCombatMode(region: RegionDefinition): Boolean =
        region.mode?.type.equals("dual_pvp", ignoreCase = true) ||
            region.mode?.type.equals("union_war", ignoreCase = true)

    private class CombatState(val regionId: String) {
        val players: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val ready: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val gear: ConcurrentHashMap<UUID, GearSnapshot> = ConcurrentHashMap()
        @Volatile
        var active: Boolean = false
        @Volatile
        var prompted: Boolean = false
    }

    class GearSnapshot(
        val contents: Array<ItemStack?>,
        val armor: Array<ItemStack?>,
        val offhand: ItemStack?,
        val level: Int,
        val exp: Float,
        val gameMode: GameMode,
        val respawn: Location?,
    ) {
        companion object {
            fun capture(player: Player, respawn: Location?): GearSnapshot =
                GearSnapshot(
                    player.inventory.contents.map { it?.clone() }.toTypedArray(),
                    player.inventory.armorContents.map { it?.clone() }.toTypedArray(),
                    player.inventory.itemInOffHand.clone(),
                    player.level,
                    player.exp,
                    player.gameMode,
                    respawn,
                )
        }
    }
}
