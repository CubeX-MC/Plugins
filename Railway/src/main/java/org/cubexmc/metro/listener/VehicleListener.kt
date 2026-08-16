package org.cubexmc.metro.listener

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.event.TrainEnterStopEvent
import org.cubexmc.metro.event.TrainExitStopEvent
import org.cubexmc.metro.manager.LanguageManager
import org.cubexmc.metro.model.Portal
import org.cubexmc.metro.service.TicketService
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.train.TrainMovementTask
import org.cubexmc.metro.util.LocationUtil
import org.cubexmc.metro.util.MetroConstants
import org.cubexmc.metro.util.SchedulerUtil

/**
 * 处理矿车相关事件
 */
class VehicleListener(private val plugin: Metro) : Listener {

    private val currentStopKey = NamespacedKey(plugin, "current_stop_id")

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onServiceTrainEnter(event: VehicleEnterEvent) {
        val minecart = event.vehicle as? Minecart ?: return
        val player = event.entered as? Player ?: return
        val train = findServiceTrain(minecart) ?: return
        if (train.isPassenger(player)) return
        if (!train.isWaiting) {
            event.isCancelled = true
            player.sendMessage(plugin.languageManager.getMessage("passenger.cannot_board_moving"))
            return
        }

        val line = train.line
        val ticketCheck = plugin.ticketService.checkCanBoard(player, line)
        if (!ticketCheck.canBoard()) {
            event.isCancelled = true
            sendTicketCheckFailure(player, ticketCheck)
            return
        }

        val transaction = plugin.ticketService.createTransaction(player, line)
        val chargeStatus = plugin.ticketService.charge(transaction)
        if (!isSuccessfulCharge(chargeStatus)) {
            event.isCancelled = true
            sendTicketChargeFailure(player, chargeStatus, transaction)
            return
        }
        if (chargeStatus == TicketService.TicketChargeStatus.CHARGED) {
            player.sendMessage(
                plugin.languageManager.getMessage(
                    "economy.paid_boarding",
                    LanguageManager.put(LanguageManager.args(), "price", plugin.ticketService.format(transaction.price)),
                ),
            )
        }

        train.addPassenger(player, minecart)
        val colorized = ChatColor.translateAlternateColorCodes('&', line.color)
        player.sendMessage(
            plugin.languageManager.getMessage(
                "passenger.boarded",
                LanguageManager.put(
                    LanguageManager.put(LanguageManager.args(), "color_code", colorized),
                    "line_name",
                    line.name,
                ),
            ),
        )
    }

    /**
     * 监听玩家离开矿车事件
     */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onVehicleExit(event: VehicleExitEvent) {
        // 只处理玩家离开地铁矿车的情况
        val minecart = event.vehicle as? Minecart ?: return
        val player = event.exited as? Player ?: return

        val serviceTrain = findServiceTrain(minecart)
        if (serviceTrain != null) {
            serviceTrain.removePassenger(player)
            plugin.scoreboardManager?.clearPlayerDisplay(player)
            return
        }
        if (!isRailwayTrainMinecart(minecart)) return

        // 玩家下车，清除其界面显示
        plugin.scoreboardManager.clearPlayerDisplay(player)

        // 获取当前位置
        val location = minecart.location

        // 检查位置是否在停靠区上，不在则立即移除矿车
        val despawnDelay =
            if (isAtStop(location)) {
                plugin.config.getInt("settings.cart_despawn_delay", 0).toLong()
            } else {
                1L
            }

        SchedulerUtil.regionRun(
            plugin,
            location,
            {
                if (!minecart.isDead) {
                    minecart.remove()
                }
            },
            despawnDelay,
            -1L,
        )
    }

    /**
     * 监听矿车移动事件，检测脱轨和处理上坡速度
     */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onVehicleMove(event: VehicleMoveEvent) {
        // 只处理地铁矿车
        val minecart = event.vehicle as? Minecart ?: return

        val serviceTrain = findServiceTrain(minecart)
        if (serviceTrain != null) {
            handleServiceTrainMove(serviceTrain, minecart, event.to)
            return
        }
        if (!isMetroMinecart(minecart)) return

        val from = event.from
        val to = event.to

        handleStopRegionEvents(minecart, to)

        if (!LocationUtil.isOnRail(to)) {
            // 矿车已脱轨，强制乘客下车并移除矿车
            minecart.eject()
            minecart.remove()
            return
        }

        if (minecart.maxSpeed == 0.0) {
            // 如果被 TrainMovementTask 冻结，强制停止所有的微小位移
            minecart.velocity = Vector(0, 0, 0)
            return
        }

        // BLOCK_BASED 控制逻辑
        // 忽略已经被 TrainMovementTask 设置为停站状态 (maxSpeed == 0) 的矿车
        if ("BLOCK_BASED".equals(plugin.configFacade.getSpeedControlMode(), ignoreCase = true)) {
            applyBlockBasedSpeed(minecart, to)
        }

        // ---- 传送门检测 ----
        if (plugin.configFacade.isPortalsEnabled() && plugin.portalManager != null) {
            if (handlePortalTrigger(minecart, to)) {
                return // 传送后不再处理后续逻辑
            }
        }

        // 上坡时补推，防止到达坡顶后倒退。
        // 不再写死 0.4：线路 max_speed 高于该值时按线路速度推进，
        // 否则任何上坡路段都会把车速强行压到 8 格/秒
        if (minecart.maxSpeed > 0.0 && to.y > from.y) {
            val direction = LocationUtil.getDirectionVector(from, to)
            minecart.velocity = direction.multiply(0.4)
        }
    }

    /** 区域事件 (Event-Driven Architecture) 火力侦测 */
    private fun handleStopRegionEvents(minecart: Minecart, to: Location) {
        val pdc = minecart.persistentDataContainer
        val stopManager = plugin.stopManager
        val currentStop = stopManager.getStopContainingLocation(to)
        val currentStopId = currentStop?.id
        val previousStopId = pdc.get(currentStopKey, PersistentDataType.STRING)

        if (currentStopId != null && currentStopId != previousStopId) {
            // 进入了新站
            pdc.set(currentStopKey, PersistentDataType.STRING, currentStopId)
            Bukkit.getPluginManager().callEvent(TrainEnterStopEvent(minecart, currentStop))
        } else if (currentStopId == null && previousStopId != null) {
            // 离开了老站
            val previousStop = stopManager.getStop(previousStopId)
            pdc.remove(currentStopKey)
            if (previousStop != null) {
                Bukkit.getPluginManager().callEvent(TrainExitStopEvent(minecart, previousStop))
            }
        }
    }

    private fun applyBlockBasedSpeed(minecart: Minecart, to: Location) {
        val blockBelow = to.block.getRelative(BlockFace.DOWN)
        val blockTypeName = blockBelow.type.name
        val allWorldsMap = plugin.configFacade.getBlockSpeedMap()

        val worldName = to.world?.name
        val speedMap = allWorldsMap[worldName]?.takeIf { it.isNotEmpty() } ?: allWorldsMap["default"]

        val multiplier =
            when {
                speedMap == null -> null
                speedMap.containsKey(blockTypeName) -> speedMap[blockTypeName]
                speedMap.containsKey("DEFAULT") -> speedMap["DEFAULT"]
                else -> null
            }
        minecart.maxSpeed = if (multiplier == null) BASE_SPEED else BASE_SPEED * multiplier
    }

    /**
     * @return true 表示矿车已被传送，调用方应停止后续处理
     */
    private fun handlePortalTrigger(minecart: Minecart, to: Location): Boolean {
        val portalManager = plugin.portalManager ?: return false
        val triggerBlockType = plugin.configFacade.getPortalTriggerBlock()
        val blockBelow: Block = to.block.getRelative(BlockFace.DOWN)
        val blockBelow2: Block = blockBelow.getRelative(BlockFace.DOWN)

        // 矿车的坐标可能飘忽不定，如果 to.getBlock() 正好是铁轨，则 blockBelow 是支撑方块。
        // 如果 to.getBlock() 是铁轨上方的空气方块，则 blockBelow 是铁轨，blockBelow2 是支撑方块。
        // 防呆：有时候玩家直接把铁轨铺在非常特殊的高度上。
        val isTriggered =
            blockBelow.type.name == triggerBlockType ||
                blockBelow2.type.name == triggerBlockType ||
                to.block.type.name == triggerBlockType
        if (!isTriggered) {
            return false
        }

        // 尝试往下偏移一格检测（有时玩家脚踩位置低于/高于实际判定中心）
        val portal = portalManager.getPortalAt(to) ?: portalManager.getPortalAt(to.clone().subtract(0.0, 1.0, 0.0))
        if (portal == null) {
            plugin.logger.warning(
                "[Debug-Portal] 矿车经过了触发方块 (" + triggerBlockType + ")，但该坐标 " +
                    to.blockX + " " + to.blockY + " " + to.blockZ +
                    " 并没有绑定任何传送门入口！请重新站在上面执行 /rail portal create",
            )
            return false
        }

        if (!isPortalEnabledForCurrentLine(minecart, portal)) {
            return false
        }
        portalManager.teleportMinecart(minecart, portal)
        return true
    }

    /**
     * safe_mode.damage_protection：阻止其他实体攻击/破坏地铁矿车
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMetroMinecartDamage(event: VehicleDamageEvent) {
        if (!plugin.configFacade.isSafeModeDamageProtection()) {
            return
        }
        val minecart = event.vehicle as? Minecart ?: return
        if (!isRailwayTrainMinecart(minecart)) {
            return
        }
        // 阻止任何来源对地铁矿车造成伤害（包括玩家攻击）
        event.damage = 0.0
        event.isCancelled = true
    }

    /**
     * safe_mode.damage_protection：阻止地铁矿车被直接销毁
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMetroMinecartDestroy(event: VehicleDestroyEvent) {
        val minecart = event.vehicle as? Minecart ?: return
        val train = findServiceTrain(minecart)
        if (plugin.configFacade.isSafeModeDamageProtection()) {
            if (!isRailwayTrainMinecart(minecart)) return
            event.isCancelled = true
            return
        }
        if (train != null) train.service.handleTrainDerail(train)
    }

    /**
     * safe_mode.entity_push_protection：阻止其他实体与地铁矿车发生物理碰撞
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMetroMinecartCollision(event: VehicleEntityCollisionEvent) {
        if (!plugin.configFacade.isSafeModeEntityPushProtection()) {
            return
        }
        val minecart = event.vehicle as? Minecart ?: return
        if (!isRailwayTrainMinecart(minecart)) {
            return
        }
        if (minecart.passengers.contains(event.entity)) {
            return
        }
        event.isCancelled = true
        event.isCollisionCancelled = true
        event.isPickupCancelled = true
    }

    /**
     * safe_mode.entity_push_protection：
     * EntityDamageByEntityEvent 覆盖 VehicleDamageEvent 未捕获的远程/投射伤害场景
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityHitMetroMinecart(event: EntityDamageByEntityEvent) {
        if (!plugin.configFacade.isSafeModeEntityPushProtection()) {
            return
        }
        val minecart = event.entity as? Minecart ?: return
        if (!isRailwayTrainMinecart(minecart)) {
            return
        }
        event.isCancelled = true
    }

    /**
     * 检查位置是否在任何停靠区内
     */
    private fun isAtStop(location: Location): Boolean =
        plugin.stopManager.getStopContainingLocation(location) != null

    private fun isMetroMinecart(minecart: Minecart): Boolean {
        val minecartKey = MetroConstants.getMinecartKey() ?: return false
        return minecart.persistentDataContainer.has(minecartKey, PersistentDataType.BYTE)
    }

    private fun isRailwayTrainMinecart(minecart: Minecart): Boolean =
        isMetroMinecart(minecart) || findServiceTrain(minecart) != null

    private fun handleServiceTrainMove(train: TrainInstance, minecart: Minecart, to: Location) {
        if (!train.isLead(minecart)) return
        if (!LocationUtil.isOnRail(to)) {
            train.service.handleTrainDerail(train)
            return
        }

        val targetStopId = train.getTargetStopId() ?: return
        val targetStop = plugin.stopManager.getStop(targetStopId) ?: return
        val stopPoint = targetStop.stopPointLocation
        val arrived =
            if (stopPoint?.world != null && to.world != null && stopPoint.world == to.world) {
                stopPoint.distanceSquared(to) <= 4.0
            } else {
                targetStop.isInStop(to)
            }
        if (arrived) train.handleArrival(targetStop, SchedulerUtil.getCurrentTick())
    }

    private fun findServiceTrain(minecart: Minecart?): TrainInstance? {
        if (minecart == null) return null
        return plugin.lineServiceManager?.getTrainByMinecart(minecart.uniqueId)
    }

    private fun isSuccessfulCharge(status: TicketService.TicketChargeStatus): Boolean =
        status == TicketService.TicketChargeStatus.CHARGED ||
            status == TicketService.TicketChargeStatus.FREE ||
            status == TicketService.TicketChargeStatus.ECONOMY_DISABLED

    private fun sendTicketCheckFailure(player: Player, ticketCheck: TicketService.TicketCheck) {
        when (ticketCheck.status) {
            TicketService.TicketCheckStatus.INSUFFICIENT_FUNDS ->
                player.sendMessage(
                    plugin.languageManager.getMessage(
                        "economy.insufficient_funds",
                        LanguageManager.put(LanguageManager.args(), "price", ticketCheck.formattedPrice),
                    ),
                )
            TicketService.TicketCheckStatus.VAULT_UNAVAILABLE ->
                player.sendMessage(plugin.languageManager.getMessage("economy.vault_unavailable"))
            else -> player.sendMessage(plugin.languageManager.getMessage("economy.transaction_failed"))
        }
    }

    private fun sendTicketChargeFailure(
        player: Player,
        status: TicketService.TicketChargeStatus,
        transaction: TicketService.TicketTransaction,
    ) {
        when (status) {
            TicketService.TicketChargeStatus.INSUFFICIENT_FUNDS ->
                player.sendMessage(
                    plugin.languageManager.getMessage(
                        "economy.insufficient_funds",
                        LanguageManager.put(
                            LanguageManager.args(),
                            "price",
                            plugin.ticketService.format(transaction.price),
                        ),
                    ),
                )
            TicketService.TicketChargeStatus.VAULT_UNAVAILABLE ->
                player.sendMessage(plugin.languageManager.getMessage("economy.vault_unavailable"))
            else -> player.sendMessage(plugin.languageManager.getMessage("economy.transaction_failed"))
        }
    }

    private fun isPortalEnabledForCurrentLine(minecart: Minecart, portal: Portal): Boolean {
        val task = TrainMovementTask.getTaskFor(minecart) ?: return false
        return task.canUsePortal(portal.id)
    }

    private companion object {
        /** 原版动力铁轨的基础速度，BLOCK_BASED 倍率以此为基准 */
        const val BASE_SPEED = 0.4
    }
}
