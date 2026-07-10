package org.cubexmc.regions.mode

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class RaceModeService(private val plugin: RegionsPlugin) {
    private val states: ConcurrentHashMap<String, RaceState> = ConcurrentHashMap()

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
        val state = state(region)
        if (state.active) {
            player.sendMessage("§e${region.name} 的比赛已经开始，你会在下一局加入。")
            return
        }
        val maxPlayers = region.mode?.values?.get("max-players")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (maxPlayers > 0 && !state.players.contains(player.uniqueId) && state.players.size >= maxPlayers) {
            player.sendMessage("§c${region.name} 当前参赛人数已满。")
            return
        }
        state.players.add(player.uniqueId)
        state.ready.remove(player.uniqueId)
        player.sendMessage("§a你已进入 ${region.name}。输入 §e/regions game ${region.id} ready §a准备比赛。")
    }

    fun onLeave(player: Player, regionId: String, reason: String) {
        val state = states[regionId] ?: return
        state.players.remove(player.uniqueId)
        state.ready.remove(player.uniqueId)
        state.progress.remove(player.uniqueId)
        if (state.players.isEmpty()) {
            states.remove(regionId)
        } else if (state.active) {
            broadcast(state, "§e${player.name} 离开比赛。")
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
            player.sendMessage("§c你不在这个比赛场地内。")
            return true
        }
        if (state.active) {
            player.sendMessage("§e比赛已经开始。")
            return true
        }
        val startConstraint = vehicleConstraint(region, "start", 0)
        if (!validRaceState(player, startConstraint)) {
            player.sendMessage(raceStateMessage(startConstraint))
            return true
        }
        if (!nearStart(player, region)) {
            player.sendMessage("§e请先到起点附近再准备。")
            return true
        }
        state.ready.add(player.uniqueId)
        broadcast(state, "§a${player.name} 已准备 (${state.ready.size}/${state.players.size})")
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

    fun status(regionId: String): String {
        val state = states[regionId] ?: return "race idle"
        return if (state.active) {
            "race active players=${state.players.size} finished=${state.finished.size}"
        } else {
            "race waiting ready=${state.ready.size}/${state.players.size}"
        }
    }

    private fun start(region: RegionDefinition, state: RaceState, reason: String) {
        if (state.active) {
            return
        }
        val minPlayers = region.mode?.values?.get("min-players")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (state.players.size < minPlayers) {
            broadcast(state, "§e还需要更多玩家才能开始比赛 (${state.players.size}/$minPlayers)。")
            return
        }
        val start = parseLocation(region.mode?.values?.get("start"))
        if (start != null) {
            val outside = state.players.mapNotNull { Bukkit.getPlayer(it) }.filterNot { near(it.location, start, radius(region, "start-radius")) }
            if (outside.isNotEmpty() && region.mode?.values?.get("require-start")?.toBooleanStrictOrNull() != false) {
                broadcast(state, "§e还有玩家不在起点附近，比赛暂不能开始。")
                return
            }
        }
        val invalidState = state.players.mapNotNull { Bukkit.getPlayer(it) }
            .filterNot { validRaceState(it, vehicleConstraint(region, "start", 0)) }
        if (invalidState.isNotEmpty()) {
            broadcast(state, "§e还有玩家不满足起点检查方式: ${describeVehicleConstraint(vehicleConstraint(region, "start", 0))}。")
            return
        }
        state.active = true
        state.startedAtMillis = System.currentTimeMillis()
        state.finished.clear()
        state.finishOrder.clear()
        for (playerId in state.players.toList()) {
            state.progress[playerId] = 0
            val player = Bukkit.getPlayer(playerId) ?: continue
            if (region.mode?.values?.get("teleport-start")?.toBooleanStrictOrNull() == true && start != null) {
                plugin.regionScheduler().teleportAsync(player, start)
            }
            plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
        }
        broadcast(state, "§a${region.name} 比赛开始！")
        plugin.logger.fine("Started race ${region.id}: $reason")
    }

    private fun end(regionId: String, reason: String) {
        val state = states[regionId] ?: return
        val region = plugin.regions().find(regionId)
        state.active = false
        if (region != null) {
            for (playerId in state.players.toList()) {
                val player = Bukkit.getPlayer(playerId) ?: continue
                plugin.triggers().fire(RegionTrigger.ON_MODE_END, player, region)
            }
        }
        broadcast(state, "§e比赛结束。")
        states.remove(regionId)
        plugin.logger.fine("Ended race $regionId: $reason")
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
                player.sendMessage("§a通过检查点 $next/${checkpoints.size}。")
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
        player.sendMessage("§6完成比赛！名次: §e#$rank §7用时: ${elapsed / 1000.0}s")
        plugin.triggers().fire(RegionTrigger.ON_FINISH, player, region)
        broadcast(state, "§6${player.name} 完成比赛，当前名次 #$rank。")
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
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> "§e此阶段要求步行，不能骑乘载具。"
            "any", "vehicle", "any_vehicle", "any-vehicle" -> "§e此阶段要求乘坐任意载具。"
            "boat" -> "§e此阶段要求坐在船上。"
            "horse" -> "§e此阶段要求骑在马上。"
            else -> "§e此阶段要求载具/状态: ${describeVehicleConstraint(constraint)}。"
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
            "pass", "ignore", "any_state", "any-state" -> "不检查"
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> "步行"
            "any", "vehicle", "any_vehicle", "any-vehicle" -> "任意载具"
            "boat" -> "船"
            "horse" -> "马"
            "minecart" -> "矿车"
            "pig" -> "猪"
            "strider" -> "炽足兽"
            "camel" -> "骆驼"
            "donkey" -> "驴"
            "mule" -> "骡"
            "llama" -> "羊驼"
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

    private fun canJudge(sender: CommandSender, region: RegionDefinition): Boolean {
        if (sender.hasPermission("regions.admin") || sender.hasPermission("regions.region.edit")) {
            return true
        }
        val player = sender as? Player ?: return false
        val judges = region.mode?.values?.get("judges")
            ?.split(',', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: return false
        return judges.any { it.equals(player.name, ignoreCase = true) || it.equals(player.uniqueId.toString(), ignoreCase = true) }
    }

    private fun state(region: RegionDefinition): RaceState =
        states.computeIfAbsent(region.id) { RaceState(region.id) }

    private fun broadcast(state: RaceState, message: String) {
        for (playerId in state.players.toList()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                player.sendMessage(message)
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
        val world = Bukkit.getWorld(parts[0].trim()) ?: return null
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
        var startedAtMillis: Long = 0L
    }
}
