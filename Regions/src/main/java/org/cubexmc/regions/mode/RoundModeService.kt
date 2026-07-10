package org.cubexmc.regions.mode

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class RoundModeService(private val plugin: RegionsPlugin) {
    private val states: ConcurrentHashMap<String, RoundState> = ConcurrentHashMap()
    private val pendingRespawnRestores: ConcurrentHashMap<UUID, CombatModeService.GearSnapshot> = ConcurrentHashMap()
    private val gearStore = CombatGearStore(plugin, "round-escrow.yml")

    init {
        gearStore.load()
    }

    fun isRoundMode(region: RegionDefinition): Boolean =
        isRoundMode(region.mode?.type)

    fun isRoundMode(type: String?): Boolean =
        type.equals("hide_and_seek", ignoreCase = true)

    fun onEnter(player: Player, region: RegionDefinition) {
        if (!isRoundMode(region)) {
            return
        }
        val state = state(region)
        if (state.active) {
            player.sendMessage("§e${region.name} 正在进行中，你会在下一局加入。")
            return
        }
        val maxPlayers = region.mode?.values?.get("max-players")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (maxPlayers > 0 && !state.players.contains(player.uniqueId) && state.players.size >= maxPlayers) {
            player.sendMessage("§c${region.name} 当前人数已满。")
            return
        }
        state.players.add(player.uniqueId)
        state.ready.remove(player.uniqueId)
        player.sendMessage("§a你已进入 ${region.name}。输入 §e/regions game ${region.id} ready §a准备小游戏。")
    }

    fun onLeave(player: Player, regionId: String, reason: String) {
        val state = states[regionId] ?: return
        state.players.remove(player.uniqueId)
        state.ready.remove(player.uniqueId)
        state.roles.remove(player.uniqueId)
        state.found.remove(player.uniqueId)
        restoreRoundState(player, state, teleportOut = true, reason = reason)
        if (state.players.isEmpty()) {
            states.remove(regionId)
        } else if (state.active) {
            maybeEndHideAndSeek(state, reason)
        }
    }

    fun onMove(event: PlayerMoveEvent): Boolean {
        val player = event.player
        val state = states.values.firstOrNull {
            it.active && it.players.contains(player.uniqueId) && it.roles[player.uniqueId] == RoundRole.SEEKER
        } ?: return false
        if (state.seekersReleased) {
            return false
        }
        val from = event.from
        val to = event.to ?: return false
        if (from.world == to.world && from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return false
        }
        event.isCancelled = true
        player.sendMessage("§e请等待隐藏时间结束。")
        return true
    }

    fun onDamage(event: EntityDamageByEntityEvent): Boolean {
        val victim = event.entity as? Player ?: return false
        val attacker = attackingPlayer(event) ?: return false
        val state = states.values.firstOrNull {
            it.active && it.seekersReleased && it.players.contains(attacker.uniqueId) && it.players.contains(victim.uniqueId)
        } ?: return false
        if (state.roles[attacker.uniqueId] != RoundRole.SEEKER || state.roles[victim.uniqueId] != RoundRole.HIDER) {
            return false
        }
        event.isCancelled = true
        found(victim, attacker, state, "tag")
        return true
    }

    fun onDeath(event: PlayerDeathEvent): Boolean {
        val player = event.entity
        val state = states.values.firstOrNull { it.active && it.players.contains(player.uniqueId) } ?: return false
        val snapshot = state.gear.remove(player.uniqueId)
        if (snapshot != null) {
            pendingRespawnRestores[player.uniqueId] = snapshot
            gearStore.take(player.uniqueId)
            event.drops.clear()
            event.droppedExp = 0
        }
        state.players.remove(player.uniqueId)
        state.ready.remove(player.uniqueId)
        state.roles.remove(player.uniqueId)
        state.found.remove(player.uniqueId)
        state.visuals.remove(player.uniqueId)
        player.sendMessage("§e你已离开当前小游戏，复活后会恢复入场前状态。")
        maybeEndHideAndSeek(state, "death")
        return true
    }

    fun onRespawn(player: Player) {
        val snapshot = pendingRespawnRestores.remove(player.uniqueId) ?: return
        restoreSnapshot(player, snapshot)
        snapshot.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
    }

    fun ready(player: Player, regionId: String): Boolean {
        val region = plugin.regions().find(regionId) ?: return false
        if (!isRoundMode(region)) {
            return false
        }
        val state = state(region)
        if (!state.players.contains(player.uniqueId)) {
            player.sendMessage("§c你不在这个小游戏场地内。")
            return true
        }
        if (state.active) {
            player.sendMessage("§e小游戏已经开始。")
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
        if (!isRoundMode(region)) {
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
        if (!isRoundMode(region)) {
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
        if (!states.containsKey(regionId)) {
            return false
        }
        end(regionId, reason)
        return true
    }

    fun cleanupAll(reason: String) {
        for (regionId in states.keys.toList()) {
            end(regionId, reason)
        }
        for ((playerId, snapshot) in pendingRespawnRestores.toMap()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                restoreSnapshot(player, snapshot)
                pendingRespawnRestores.remove(playerId)
            })
        }
        for (playerId in gearStore.allPlayerIds()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                restoreStored(player, "round-cleanup:$reason")
            })
        }
    }

    fun restoreIfPending(player: Player, reason: String): Boolean =
        restoreStored(player, reason)

    fun status(regionId: String): String {
        val state = states[regionId] ?: return "round idle"
        val hiders = state.roles.values.count { it == RoundRole.HIDER }
        val seekers = state.roles.values.count { it == RoundRole.SEEKER }
        return if (state.active) {
            "round active seekers=$seekers hiders=$hiders found=${state.found.size} released=${state.seekersReleased}"
        } else {
            "round waiting ready=${state.ready.size}/${state.players.size}"
        }
    }

    private fun start(region: RegionDefinition, state: RoundState, reason: String) {
        if (state.active) {
            return
        }
        val minPlayers = region.mode?.values?.get("min-players")?.toIntOrNull()?.coerceAtLeast(2) ?: 2
        if (state.players.size < minPlayers) {
            broadcast(state, "§e还需要更多玩家才能开始小游戏 (${state.players.size}/$minPlayers)。")
            return
        }
        state.active = true
        state.ready.clear()
        state.roles.clear()
        state.found.clear()
        state.seekersReleased = false
        state.startedAtMillis = System.currentTimeMillis()
        assignHideAndSeekRoles(region, state)
        for (playerId in state.players.toList()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                applyRoundStart(player, region, state)
                plugin.triggers().fire(RegionTrigger.ON_ROLE_ASSIGNED, player, region)
                plugin.triggers().fire(RegionTrigger.ON_MODE_START, player, region)
            })
        }
        val hideSeconds = hideSeconds(region)
        broadcast(state, "§a${region.name} 开始！寻找者将在 ${hideSeconds} 秒后释放。")
        plugin.regionScheduler().runGlobalLater(Runnable {
            releaseSeekers(region.id)
        }, hideSeconds * 20L)
        val roundSeconds = roundSeconds(region)
        if (roundSeconds > 0) {
            plugin.regionScheduler().runGlobalLater(Runnable {
                val current = states[region.id]
                if (current != null && current.active) {
                    broadcast(current, "§6时间到，隐藏者获胜！")
                    end(region.id, "time-limit")
                }
            }, roundSeconds * 20L)
        }
        plugin.logger.fine("Started round ${region.id}: $reason")
    }

    private fun assignHideAndSeekRoles(region: RegionDefinition, state: RoundState) {
        val players = state.players.toMutableList()
        players.shuffle()
        val seekerCount = seekerCount(region, players.size)
        for ((index, playerId) in players.withIndex()) {
            state.roles[playerId] = if (index < seekerCount) RoundRole.SEEKER else RoundRole.HIDER
        }
    }

    private fun applyRoundStart(player: Player, region: RegionDefinition, state: RoundState) {
        val role = state.roles[player.uniqueId] ?: return
        plugin.sessions().setMetadata(player, region.id, "round_role", role.key)
        state.visuals.putIfAbsent(player.uniqueId, RoundVisualSnapshot.capture(player))
        if (shouldReplaceGear(region) && !state.gear.containsKey(player.uniqueId)) {
            val snapshot = CombatModeService.GearSnapshot.capture(player, outsideLocation(region))
            if (state.gear.putIfAbsent(player.uniqueId, snapshot) == null) {
                gearStore.put(player.uniqueId, region.id, snapshot)
            }
        }
        when (role) {
            RoundRole.SEEKER -> {
                applySeekerVisual(player)
                if (shouldReplaceGear(region)) {
                    applyKit(player, region.mode?.values?.get("seeker-kit") ?: region.mode?.values?.get("kit"))
                }
                player.sendMessage("§c你的身份: 寻找者。等待倒计时结束后，攻击隐藏者即可找到对方。")
            }
            RoundRole.HIDER -> {
                applyHiderVisual(player)
                if (shouldReplaceGear(region)) {
                    applyKit(player, region.mode?.values?.get("hider-kit") ?: region.mode?.values?.get("kit"))
                }
                player.sendMessage("§a你的身份: 隐藏者。倒计时结束前赶快藏好，被寻找者攻击会出局。")
            }
        }
    }

    private fun releaseSeekers(regionId: String) {
        val state = states[regionId] ?: return
        if (!state.active) {
            return
        }
        state.seekersReleased = true
        broadcast(state, "§c寻找者已释放！")
    }

    private fun found(hider: Player, seeker: Player, state: RoundState, reason: String) {
        if (!state.found.add(hider.uniqueId)) {
            return
        }
        val region = plugin.regions().find(state.regionId) ?: return
        plugin.sessions().setMetadata(hider, region.id, "round_found", "true")
        plugin.sessions().setMetadata(hider, region.id, "round_found_by", seeker.name)
        restoreRoundVisual(hider, state)
        if (region.mode?.values?.get("found-becomes-seeker")?.toBooleanStrictOrNull() != false) {
            state.roles[hider.uniqueId] = RoundRole.SEEKER
            applySeekerVisual(hider)
            hider.sendMessage("§e你被 ${seeker.name} 找到了，现在加入寻找者。")
        } else {
            state.roles.remove(hider.uniqueId)
            outsideLocation(region)?.let { plugin.regionScheduler().teleportAsync(hider, it) }
            hider.sendMessage("§e你被 ${seeker.name} 找到了，已离开本局。")
        }
        plugin.triggers().fire(RegionTrigger.ON_FOUND, hider, region)
        broadcast(state, "§6${hider.name} 被 ${seeker.name} 找到了。")
        plugin.logger.fine("Hide-and-seek found ${hider.name} by ${seeker.name} in ${state.regionId}: $reason")
        maybeEndHideAndSeek(state, "all-found-check")
    }

    private fun maybeEndHideAndSeek(state: RoundState, reason: String) {
        if (!state.active) {
            return
        }
        val remainingHiders = state.players.count { state.roles[it] == RoundRole.HIDER && !state.found.contains(it) }
        val seekers = state.players.count { state.roles[it] == RoundRole.SEEKER }
        if (remainingHiders <= 0) {
            broadcast(state, "§c所有隐藏者都被找到了，寻找者获胜！")
            end(state.regionId, reason)
        } else if (seekers <= 0) {
            broadcast(state, "§6寻找者离开了游戏，隐藏者获胜！")
            end(state.regionId, reason)
        }
    }

    private fun end(regionId: String, reason: String) {
        val state = states[regionId] ?: return
        val region = plugin.regions().find(regionId)
        state.active = false
        for (playerId in (state.players + state.gear.keys + state.visuals.keys).toSet()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                if (region != null) {
                    plugin.triggers().fire(RegionTrigger.ON_MODE_END, player, region)
                }
                restoreRoundState(player, state, teleportOut = false, reason = reason)
                player.sendMessage("§e小游戏结束，你的临时状态已恢复。")
            })
        }
        states.remove(regionId)
        plugin.logger.fine("Ended round $regionId: $reason")
    }

    private fun restoreRoundState(player: Player, state: RoundState, teleportOut: Boolean, reason: String) {
        restoreRoundVisual(player, state)
        val snapshot = state.gear.remove(player.uniqueId)
        if (snapshot != null) {
            restoreSnapshot(player, snapshot)
            if (teleportOut) {
                snapshot.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
            }
            plugin.logger.fine("Restored round gear for ${player.name} in ${state.regionId}: $reason")
        }
    }

    private fun applyHiderVisual(player: Player) {
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, 20 * 60 * 60, 0, false, false, false))
        player.isGlowing = false
    }

    private fun applySeekerVisual(player: Player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY)
        player.isGlowing = false
    }

    private fun restoreRoundVisual(player: Player, state: RoundState) {
        val snapshot = state.visuals.remove(player.uniqueId) ?: return
        player.removePotionEffect(PotionEffectType.INVISIBILITY)
        if (snapshot.invisibility != null) {
            player.addPotionEffect(snapshot.invisibility)
        }
        player.isGlowing = snapshot.glowing
    }

    private fun restoreSnapshot(player: Player, snapshot: CombatModeService.GearSnapshot) {
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
        plugin.logger.warning("Restored persisted round escrow for ${player.name}: $reason")
        return true
    }

    private fun applyKit(player: Player, raw: String?) {
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(null)
        for (item in parseItems(raw)) {
            player.inventory.addItem(item)
        }
        player.updateInventory()
    }

    private fun parseItems(value: String?): List<ItemStack> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return value.split(',', ';')
            .mapNotNull { raw ->
                val parts = raw.trim().split(':')
                val material = Material.matchMaterial(parts[0].trim().uppercase(Locale.ROOT)) ?: return@mapNotNull null
                val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                ItemStack(material, amount)
            }
    }

    private fun shouldReplaceGear(region: RegionDefinition): Boolean {
        val values = region.mode?.values ?: return false
        return values["replace-gear"]?.toBooleanStrictOrNull() == true ||
            !values["kit"].isNullOrBlank() ||
            !values["seeker-kit"].isNullOrBlank() ||
            !values["hider-kit"].isNullOrBlank()
    }

    private fun seekerCount(region: RegionDefinition, playerCount: Int): Int {
        val explicit = region.mode?.values?.get("seekers")?.toIntOrNull()
        if (explicit != null) {
            return explicit.coerceIn(1, (playerCount - 1).coerceAtLeast(1))
        }
        val ratio = region.mode?.values?.get("seeker-ratio")?.toDoubleOrNull()?.coerceIn(0.05, 0.8) ?: 0.2
        return ceil(playerCount * ratio).toInt().coerceIn(1, (playerCount - 1).coerceAtLeast(1))
    }

    private fun hideSeconds(region: RegionDefinition): Long =
        region.mode?.values?.get("hide-seconds")?.toLongOrNull()?.coerceAtLeast(0L) ?: 30L

    private fun roundSeconds(region: RegionDefinition): Long =
        region.mode?.values?.get("round-seconds")?.toLongOrNull()?.coerceAtLeast(0L) ?: 300L

    private fun requiredVotes(region: RegionDefinition, state: RoundState): Int {
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

    private fun state(region: RegionDefinition): RoundState =
        states.computeIfAbsent(region.id) { RoundState(region.id) }

    private fun broadcast(state: RoundState, message: String) {
        for (playerId in state.players.toList()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            plugin.regionScheduler().runAtEntity(player, Runnable {
                player.sendMessage(message)
            })
        }
    }

    private fun outsideLocation(region: RegionDefinition): Location? {
        val raw = region.mode?.values?.get("respawn")
            ?: region.mode?.values?.get("outside")
            ?: region.mode?.values?.get("spectator")
            ?: return null
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

    private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? {
        val direct = event.damager
        if (direct is Player) {
            return direct
        }
        val shooter = (direct as? org.bukkit.entity.Projectile)?.shooter
        return shooter as? Player
    }

    private enum class RoundRole(val key: String) {
        SEEKER("seeker"),
        HIDER("hider"),
    }

    private class RoundState(val regionId: String) {
        val players: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val ready: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val found: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val roles: ConcurrentHashMap<UUID, RoundRole> = ConcurrentHashMap()
        val gear: ConcurrentHashMap<UUID, CombatModeService.GearSnapshot> = ConcurrentHashMap()
        val visuals: ConcurrentHashMap<UUID, RoundVisualSnapshot> = ConcurrentHashMap()
        @Volatile
        var active: Boolean = false
        @Volatile
        var seekersReleased: Boolean = false
        @Volatile
        var startedAtMillis: Long = 0L
    }

    private class RoundVisualSnapshot(
        val invisibility: PotionEffect?,
        val glowing: Boolean,
    ) {
        companion object {
            fun capture(player: Player): RoundVisualSnapshot =
                RoundVisualSnapshot(player.getPotionEffect(PotionEffectType.INVISIBILITY), player.isGlowing)
        }
    }
}
