package org.cubexmc.regions.mode

import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class RaceModeService(private val plugin: RegionsPlugin) {
    private val states: ConcurrentHashMap<String, RaceState> = ConcurrentHashMap()
    private val endingRegions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isRaceMode(region: RegionDefinition): Boolean =
        isRaceMode(region.mode?.type)

    fun isRaceMode(type: String?): Boolean =
        type.equals("run_race", ignoreCase = true) ||
            type.equals("boat_race", ignoreCase = true) ||
            type.equals("horse_race", ignoreCase = true)

    fun onEnter(player: Player, region: RegionDefinition) {
        if (!isRaceMode(region)) {
            return
        }
        if (endingRegions.contains(region.id)) {
            plugin.sendGame(player, plugin.gameText("game.race.restoring", mapOf("name" to region.name)))
            return
        }
        val state = state(region)
        if (state.active) {
            plugin.sendGame(player, plugin.gameText("game.race.in-progress", mapOf("name" to region.name)))
            return
        }
        val maxPlayers = region.mode?.values?.get("max-players")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (maxPlayers > 0 && !state.players.contains(player.uniqueId) && state.players.size >= maxPlayers) {
            plugin.sendGame(player, plugin.gameText("game.race.full", mapOf("name" to region.name)))
            return
        }
        state.players.add(player.uniqueId)
        state.ready.remove(player.uniqueId)
        plugin.sendGame(player, plugin.gameText("game.race.joined", mapOf("name" to region.name, "id" to region.id)))
    }

    fun onLeave(player: Player, regionId: String, reason: String) {
        val state = states[regionId] ?: return
        state.players.remove(player.uniqueId)
        state.ready.remove(player.uniqueId)
        state.progress.remove(player.uniqueId)
        if (state.players.isEmpty()) {
            states.remove(regionId)
        } else if (state.active) {
            broadcast(state, plugin.gameText("game.race.left", mapOf("player" to player.name)))
        }
    }

    fun onMove(player: Player) {
        for (session in plugin.sessions().activeSessions(player.uniqueId)) {
            val region = plugin.regions().find(session.regionId) ?: continue
            if (!isRaceMode(region)) {
                continue
            }
            val state = states[region.id] ?: continue
            if (!state.active || !state.players.contains(player.uniqueId) || state.finished.contains(player.uniqueId)) {
                continue
            }
            tickRaceProgress(player, region, state)
        }
    }

    fun ready(player: Player, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRaceMode(region)) {
            return false
        }
        val state = state(region)
        if (!state.players.contains(player.uniqueId)) {
            plugin.sendGame(player, plugin.gameText("game.race.not-inside"))
            return true
        }
        if (state.active) {
            plugin.sendGame(player, plugin.gameText("game.race.already-started"))
            return true
        }
        val startConstraint = vehicleConstraint(region, "start", 0)
        if (!validRaceState(player, startConstraint)) {
            plugin.sendGame(player, raceStateMessage(startConstraint))
            return true
        }
        if (!nearStart(player, region)) {
            plugin.sendGame(player, plugin.gameText("game.race.start-required"))
            return true
        }
        state.ready.add(player.uniqueId)
        broadcast(state, plugin.gameText("game.race.ready", mapOf("player" to player.name, "current" to state.ready.size.toString(), "total" to state.players.size.toString())))
        if (startMode(region) == "vote" && state.ready.size >= requiredVotes(region, state)) {
            start(region, state, "vote")
        }
        return true
    }

    fun startCommand(sender: CommandSender, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRaceMode(region)) {
            return false
        }
        if (!canJudge(sender, region)) {
            plugin.lang().send(sender, "no-permission")
            return true
        }
        start(region, state(region), "judge")
        return true
    }

    fun forceEnd(sender: CommandSender, regionId: String, reason: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRaceMode(region)) {
            return false
        }
        if (!canJudge(sender, region)) {
            plugin.lang().send(sender, "no-permission")
            return true
        }
        end(regionId, reason)
        return true
    }

    fun forceEnd(regionId: String, reason: String): Boolean {
        if (!states.containsKey(regionId)) return false
        end(regionId, reason)
        return true
    }

    fun cleanupAll(reason: String, shuttingDown: Boolean = false) {
        val immediate = !plugin.regionScheduler().isFolia
        for (regionId in states.keys.toList()) {
            end(regionId, reason, immediate = immediate, restorePlayers = !shuttingDown || immediate)
        }
    }

    fun status(regionId: String): String {
        val state = states[regionId] ?: return "race idle"
        return if (state.active) {
            "race active players=${state.players.size} finished=${state.finished.size}"
        } else {
            "race waiting ready=${state.ready.size}/${state.players.size}"
        }
    }

    private fun start(region: RegionDefinition, state: RaceState, reason: String) {
        if (state.active || state.starting) {
            return
        }
        val minPlayers = region.mode?.values?.get("min-players")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (state.players.size < minPlayers) {
            broadcast(state, plugin.gameText("game.race.waiting-players", mapOf("current" to state.players.size.toString(), "required" to minPlayers.toString())))
            return
        }
        state.starting = true
        val start = parseLocation(region.mode?.values?.get("start"))
        val playerIds = state.players.toList()
        val checks = ConcurrentHashMap<UUID, RaceStartCheck>()
        val remaining = AtomicInteger(playerIds.size)
        fun completeCheck() {
            if (remaining.decrementAndGet() == 0) {
                finalizeStart(region, state, reason, start, checks)
            }
        }
        for (playerId in playerIds) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                checks[playerId] = RaceStartCheck(atStart = false, validVehicle = false)
                completeCheck()
                continue
            }
            plugin.regionScheduler().runAtEntity(player, Runnable {
                checks[playerId] = RaceStartCheck(
                    atStart = start == null || near(player.location, start, radius(region, "start-radius")),
                    validVehicle = validRaceState(player, vehicleConstraint(region, "start", 0)),
                )
                completeCheck()
            })
        }
    }

    private fun finalizeStart(
        region: RegionDefinition,
        state: RaceState,
        reason: String,
        start: Location?,
        checks: Map<UUID, RaceStartCheck>,
    ) {
        if (states[region.id] !== state || !state.starting || state.active) return
        state.starting = false
        val currentPlayers = state.players.toSet()
        val minPlayers = region.mode?.values?.get("min-players")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (currentPlayers.size < minPlayers) {
            broadcast(state, plugin.gameText("game.race.waiting-players", mapOf("current" to currentPlayers.size.toString(), "required" to minPlayers.toString())))
            return
        }
        if (!checks.keys.containsAll(currentPlayers)) return
        if (
            region.mode?.values?.get("require-start")?.toBooleanStrictOrNull() != false &&
            currentPlayers.any { checks[it]?.atStart != true }
        ) {
            broadcast(state, plugin.gameText("game.race.waiting-at-start"))
            return
        }
        if (currentPlayers.any { checks[it]?.validVehicle != true }) {
            broadcast(state, plugin.gameText("game.race.waiting-vehicle", mapOf("vehicle" to describeVehicleConstraint(vehicleConstraint(region, "start", 0)))))
            return
        }
        state.active = true
        state.startedAtMillis = System.currentTimeMillis()
        state.finished.clear()
        state.finishOrder.clear()
        for (playerId in state.players.toList()) {
            state.progress[playerId] = 0
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                if (states[region.id] !== state || !state.active || !state.players.contains(playerId)) return@Runnable
                if (region.mode?.values?.get("teleport-start")?.toBooleanStrictOrNull() == true && start != null) {
                    plugin.regionScheduler().teleportAsync(player, start)
                }
                plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
            })
        }
        broadcast(state, plugin.gameText("game.race.started", mapOf("name" to region.name)))
        val timeoutSeconds = raceTimeoutSeconds(region)
        if (timeoutSeconds > 0) {
            plugin.regionScheduler().runGlobalLater(Runnable {
                if (states[region.id] === state && state.active) {
                    broadcast(state, plugin.gameText("game.race.timeout"))
                    end(region.id, "time-limit")
                }
            }, timeoutSeconds * 20L)
        }
        plugin.log().debug("Started race ${region.id}: $reason")
    }

    private fun end(
        regionId: String,
        reason: String,
        immediate: Boolean = !plugin.regionScheduler().isFolia,
        restorePlayers: Boolean = true,
    ) {
        val state = states[regionId] ?: return
        val region = plugin.regions().find(regionId)
        state.active = false
        state.starting = false
        endingRegions.add(regionId)
        states.remove(regionId, state)
        plugin.audit().record(
            null,
            regionId,
            "mode.race.end",
            reason,
            mapOf(
                "revision" to (region?.publishedRevision?.toString() ?: "unknown"),
                "participants" to state.players.size.toString(),
                "finishers" to state.finishOrder.size.toString(),
                "finish-order" to state.finishOrder.joinToString(","),
            ),
        )
        if (region != null && restorePlayers) {
            val players = state.players.mapNotNull { plugin.server.getPlayer(it) }
            val remaining = AtomicInteger(players.size)
            if (players.isEmpty()) endingRegions.remove(regionId)
            for (player in players) {
                runCatching {
                    val restore = Runnable {
                        try {
                            plugin.triggers().fire(RegionTrigger.ON_MODE_END, player, region)
                            plugin.effects().cleanupModeEffects(player, regionId, "mode-end:$reason")
                        } finally {
                            if (remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                        }
                    }
                    if (immediate) restore.run() else plugin.regionScheduler().runAtEntity(player, restore)
                }.onFailure {
                    plugin.log().severe("Failed to schedule race cleanup for ${player.name} in $regionId: ${it.message}")
                    if (!immediate && remaining.decrementAndGet() == 0) endingRegions.remove(regionId)
                }
            }
        } else {
            endingRegions.remove(regionId)
        }
        broadcast(state, plugin.gameText("game.race.ended"))
        plugin.log().debug("Ended race $regionId: $reason")
    }

    private fun tickRaceProgress(player: Player, region: RegionDefinition, state: RaceState) {
        val checkpoints = parseLocations(region.mode?.values?.get("checkpoints"))
        val index = state.progress[player.uniqueId] ?: 0
        if (index < checkpoints.size) {
            val constraint = vehicleConstraint(region, "checkpoint", index)
            if (!validRaceState(player, constraint)) {
                return
            }
            val nextCheckpoint = checkpoints[index]
            if (near(player.location, nextCheckpoint, radius(region, "checkpoint-radius"))) {
                val next = index + 1
                state.progress[player.uniqueId] = next
                plugin.sessions().setMetadata(player, region.id, "race_checkpoint", next.toString())
                plugin.sendGame(player, plugin.gameText("game.race.checkpoint", mapOf("index" to next.toString(), "total" to checkpoints.size.toString())))
                plugin.triggers().fire(RegionTrigger.ON_CHECKPOINT, player, region)
            }
            return
        }
        val finish = parseLocation(region.mode?.values?.get("finish")) ?: return
        if (!validRaceState(player, vehicleConstraint(region, "finish", checkpoints.size))) {
            return
        }
        if (!near(player.location, finish, radius(region, "finish-radius"))) {
            return
        }
        val rank = state.finishOrder.size + 1
        state.finishOrder.add(player.uniqueId)
        state.finished.add(player.uniqueId)
        val elapsed = System.currentTimeMillis() - state.startedAtMillis
        plugin.sessions().setMetadata(player, region.id, "race_rank", rank.toString())
        plugin.sessions().setMetadata(player, region.id, "race_time_ms", elapsed.toString())
        plugin.audit().record(
            player,
            region.id,
            "mode.race.finish",
            details = mapOf(
                "revision" to (region.publishedRevision?.toString() ?: "unknown"),
                "rank" to rank.toString(),
                "elapsed-ms" to elapsed.toString(),
            ),
        )
        plugin.sendGame(player, plugin.gameText("game.race.your-result", mapOf("rank" to rank.toString(), "time" to (elapsed / 1000.0).toString())))
        plugin.triggers().fire(RegionTrigger.ON_FINISH, player, region)
        broadcast(state, plugin.gameText("game.race.finished", mapOf("player" to player.name, "rank" to rank.toString())))
        if (state.finished.containsAll(state.players)) {
            end(region.id, "all-finished")
        }
    }

    private fun validRaceState(player: Player, constraint: String): Boolean {
        val vehicle = player.vehicle
        val type = vehicle?.type?.name?.lowercase(Locale.ROOT).orEmpty()
        return when (constraint.lowercase(Locale.ROOT)) {
            "pass", "ignore", "any_state", "any-state" -> true
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> vehicle == null
            "any", "vehicle", "any_vehicle", "any-vehicle" -> vehicle != null
            "boat" -> vehicle != null && type.contains("boat")
            "horse" -> vehicle != null && type.contains("horse")
            "minecart" -> vehicle != null && type.contains("minecart")
            "pig" -> vehicle != null && type == "pig"
            "strider" -> vehicle != null && type == "strider"
            "camel" -> vehicle != null && type == "camel"
            "donkey" -> vehicle != null && type == "donkey"
            "mule" -> vehicle != null && type == "mule"
            "llama" -> vehicle != null && type.contains("llama")
            else -> vehicle != null && type.equals(constraint.lowercase(Locale.ROOT), ignoreCase = true)
        }
    }

    private fun raceStateMessage(constraint: String): String =
        when (constraint.lowercase(Locale.ROOT)) {
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> plugin.gameText("game.race.require.on-foot")
            "any", "vehicle", "any_vehicle", "any-vehicle" -> plugin.gameText("game.race.require.any")
            "boat" -> plugin.gameText("game.race.require.boat")
            "horse" -> plugin.gameText("game.race.require.horse")
            else -> plugin.gameText("game.race.require.other", mapOf("vehicle" to describeVehicleConstraint(constraint)))
        }

    private fun vehicleConstraint(region: RegionDefinition, stage: String, checkpointIndex: Int): String {
        val values = region.mode?.values ?: emptyMap()
        if (stage == "checkpoint") {
            val checkpointConstraint = parseConstraintList(values["checkpoint-vehicles"]).getOrNull(checkpointIndex)
            if (!checkpointConstraint.isNullOrBlank()) {
                return checkpointConstraint
            }
        }
        val stageValue = values["$stage-vehicle"]
        if (!stageValue.isNullOrBlank()) {
            return stageValue
        }
        return values["vehicle"] ?: defaultVehicleConstraint(region.mode?.type)
    }

    private fun defaultVehicleConstraint(type: String?): String =
        when (type?.lowercase(Locale.ROOT)) {
            "boat_race" -> "boat"
            "horse_race" -> "horse"
            "run_race" -> "none"
            else -> "pass"
        }

    private fun parseConstraintList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val delimiter = if (raw.contains(';')) ';' else ','
        return raw.split(delimiter).map { it.trim() }
    }

    private fun describeVehicleConstraint(constraint: String): String =
        when (constraint.lowercase(Locale.ROOT)) {
            "pass", "ignore", "any_state", "any-state" -> plugin.gameText("gui.vehicle.ignore")
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> plugin.gameText("gui.vehicle.on-foot")
            "any", "vehicle", "any_vehicle", "any-vehicle" -> plugin.gameText("gui.vehicle.any")
            "boat" -> plugin.gameText("gui.vehicle.boat")
            "horse" -> plugin.gameText("gui.vehicle.horse")
            "minecart" -> plugin.gameText("gui.vehicle.minecart")
            "pig" -> plugin.gameText("game.vehicle.pig")
            "strider" -> plugin.gameText("game.vehicle.strider")
            "camel" -> plugin.gameText("game.vehicle.camel")
            "donkey" -> plugin.gameText("game.vehicle.donkey")
            "mule" -> plugin.gameText("game.vehicle.mule")
            "llama" -> plugin.gameText("game.vehicle.llama")
            else -> constraint
        }

    private fun nearStart(player: Player, region: RegionDefinition): Boolean {
        if (region.mode?.values?.get("require-start")?.toBooleanStrictOrNull() == false) {
            return true
        }
        val start = parseLocation(region.mode?.values?.get("start")) ?: return true
        return near(player.location, start, radius(region, "start-radius"))
    }

    private fun requiredVotes(region: RegionDefinition, state: RaceState): Int {
        val percent = region.mode?.values?.get("vote-start-percent")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
        return ceil(state.players.size * percent).toInt().coerceAtLeast(1)
    }

    private fun startMode(region: RegionDefinition): String =
        region.mode?.values?.get("start-mode")?.lowercase(Locale.ROOT) ?: "vote"

    private fun raceTimeoutSeconds(region: RegionDefinition): Long =
        (region.mode?.values?.get("timeout-seconds")
            ?: region.mode?.values?.get("max-duration-seconds")
            ?: region.mode?.values?.get("duration-seconds"))
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: DEFAULT_TIMEOUT_SECONDS

    /** 场主始终可以，场主指定的裁判团队额外可以。规则见 [RegionAuthorityService.canJudge]。 */
    private fun canJudge(sender: CommandSender, region: RegionDefinition): Boolean {
        return plugin.authority().canJudge(sender, region).allowed
    }

    private fun state(region: RegionDefinition): RaceState =
        states.computeIfAbsent(region.id) { RaceState(region.id) }

    private fun broadcast(state: RaceState, message: String) {
        for (playerId in state.players.toList()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                plugin.sendGame(player, message)
            })
        }
    }

    private fun radius(region: RegionDefinition, key: String): Double =
        region.mode?.values?.get(key)?.toDoubleOrNull()
            ?: region.mode?.values?.get("radius")?.toDoubleOrNull()
            ?: 2.5

    private fun near(current: Location, target: Location, radius: Double): Boolean {
        if (current.world?.uid != target.world?.uid) {
            return false
        }
        return current.distanceSquared(target) <= radius * radius
    }

    private fun parseLocations(raw: String?): List<Location> =
        raw?.split(';')
            ?.mapNotNull { parseLocation(it) }
            ?: emptyList()

    private fun parseLocation(raw: String?): Location? {
        if (raw.isNullOrBlank()) {
            return null
        }
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

    private class RaceState(val regionId: String) {
        val players: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val ready: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val progress: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()
        val finished: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val finishOrder: MutableList<UUID> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile
        var active: Boolean = false
        @Volatile
        var starting: Boolean = false
        @Volatile
        var startedAtMillis: Long = 0L
    }

    private data class RaceStartCheck(
        val atStart: Boolean,
        val validVehicle: Boolean,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300L
    }
}
