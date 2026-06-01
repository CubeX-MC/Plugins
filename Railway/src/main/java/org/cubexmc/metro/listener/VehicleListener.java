package org.cubexmc.metro.listener;

import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.event.TrainEnterStopEvent;
import org.cubexmc.metro.event.TrainExitStopEvent;
import org.cubexmc.metro.manager.LanguageManager;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.Portal;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.service.TicketService;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.train.TrainMovementTask;
import org.cubexmc.metro.util.LocationUtil;
import org.cubexmc.metro.util.MetroConstants;
import org.cubexmc.metro.util.SchedulerUtil;

/**
 * 处理矿车相关事件
 */
public class VehicleListener implements Listener {

    private final Metro plugin;
    private final org.bukkit.NamespacedKey CURRENT_STOP_KEY;

    public VehicleListener(Metro plugin) {
        this.plugin = plugin;
        this.CURRENT_STOP_KEY = new org.bukkit.NamespacedKey(plugin, "current_stop_id");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onServiceTrainEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart) || !(event.getEntered() instanceof Player player)) {
            return;
        }

        TrainInstance train = findServiceTrain(minecart);
        if (train == null) {
            return;
        }
        if (train.isPassenger(player)) {
            return;
        }
        if (!train.isWaiting()) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().getMessage("passenger.cannot_board_moving"));
            return;
        }

        Line line = train.getLine();
        TicketService.TicketCheck ticketCheck = plugin.getTicketService().checkCanBoard(player, line);
        if (!ticketCheck.canBoard()) {
            event.setCancelled(true);
            sendTicketCheckFailure(player, ticketCheck);
            return;
        }

        TicketService.TicketTransaction transaction = plugin.getTicketService().createTransaction(player, line);
        TicketService.TicketChargeStatus chargeStatus = plugin.getTicketService().charge(transaction);
        if (!isSuccessfulCharge(chargeStatus)) {
            event.setCancelled(true);
            sendTicketChargeFailure(player, chargeStatus, transaction);
            return;
        }
        if (chargeStatus == TicketService.TicketChargeStatus.CHARGED) {
            player.sendMessage(plugin.getLanguageManager().getMessage("economy.paid_boarding",
                    LanguageManager.put(LanguageManager.args(), "price",
                            plugin.getTicketService().format(transaction.getPrice()))));
        }

        train.addPassenger(player, minecart);
        String colorized = ChatColor.translateAlternateColorCodes('&', line.getColor());
        player.sendMessage(plugin.getLanguageManager().getMessage("passenger.boarded",
                LanguageManager.put(LanguageManager.put(LanguageManager.args(),
                        "color", colorized), "line_name", line.getName())));
    }

    /**
     * 监听玩家离开矿车事件
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleExit(VehicleExitEvent event) {
        Vehicle vehicle = event.getVehicle();
        Entity passenger = event.getExited();

        // 只处理玩家离开地铁矿车的情况
        if (!(vehicle instanceof Minecart) || !(passenger instanceof Player)) {
            return;
        }

        Player player = (Player) passenger;
        Minecart minecart = (Minecart) vehicle;

        TrainInstance train = findServiceTrain(minecart);
        if (train != null) {
            train.removePassenger(player);
            if (plugin.getScoreboardManager() != null) {
                plugin.getScoreboardManager().clearPlayerDisplay(player);
            }
            return;
        }

        // 检查是否是Metro的矿车
        if (!isRailwayTrainMinecart(minecart)) {
            return;
        }

        // 玩家下车，清除其界面显示
        plugin.getScoreboardManager().clearPlayerDisplay(player);

        // 获取当前位置
        Location location = minecart.getLocation();

        // 检查位置是否在停靠区上
        if (!isAtStop(location)) {
            // 如果不在停靠区上，立即移除矿车
            final Minecart finalMinecart = minecart; // 创建final引用以便在Lambda中使用
            SchedulerUtil.regionRun(plugin, location, () -> {
                if (finalMinecart != null && !finalMinecart.isDead()) {
                    finalMinecart.remove();
                }
            }, 1L, -1); // 1 tick后移除
            return;
        }

        // 如果在停靠区上，根据配置延迟移除矿车
        int despawnDelay = plugin.getConfig().getInt("settings.cart_despawn_delay", 0);

        final Minecart finalMinecart = minecart; // 创建final引用以便在Lambda中使用
        SchedulerUtil.regionRun(plugin, location, () -> {
            if (finalMinecart != null && !finalMinecart.isDead()) {
                finalMinecart.remove();
            }
        }, despawnDelay, -1); // 使用配置的延迟时间
    }

    /**
     * 监听矿车移动事件，检测脱轨和处理上坡速度
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();

        // 只处理地铁矿车
        if (!(vehicle instanceof Minecart)) {
            return;
        }

        Minecart minecart = (Minecart) vehicle;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        TrainInstance serviceTrain = findServiceTrain(minecart);
        if (serviceTrain != null) {
            handleServiceTrainMove(serviceTrain, minecart, to);
            return;
        }

        // 检查是否是Metro的矿车
        if (!isMetroMinecart(minecart)) {
            return;
        }

        PersistentDataContainer pdc = minecart.getPersistentDataContainer();
        
        // --- 区域事件 (Event-Driven Architecture) 火力侦测 ---
        StopManager stopManager = plugin.getStopManager();
        Stop currentStop = stopManager.getStopContainingLocation(to);
        String currentStopId = currentStop != null ? currentStop.getId() : null;
        String previousStopId = pdc.get(CURRENT_STOP_KEY, PersistentDataType.STRING);
        
        if (currentStopId != null && !currentStopId.equals(previousStopId)) {
            // 进入了新站
            pdc.set(CURRENT_STOP_KEY, PersistentDataType.STRING, currentStopId);
            TrainEnterStopEvent enterEvent = new TrainEnterStopEvent(minecart, currentStop);
            org.bukkit.Bukkit.getPluginManager().callEvent(enterEvent);
        } else if (currentStopId == null && previousStopId != null) {
            // 离开了老站
            Stop previousStop = stopManager.getStop(previousStopId);
            pdc.remove(CURRENT_STOP_KEY);
            if (previousStop != null) {
                TrainExitStopEvent exitEvent = new TrainExitStopEvent(minecart, previousStop);
                org.bukkit.Bukkit.getPluginManager().callEvent(exitEvent);
            }
        }

        if (LocationUtil.isOnRail(to)) {
            if (minecart.getMaxSpeed() == 0.0) {
                // 如果被 TrainMovementTask 冻结，强制停止所有的微小位移
                minecart.setVelocity(new Vector(0, 0, 0));
                return;
            }

            // BLOCK_BASED 控制逻辑
            // 忽略已经被 TrainMovementTask 设置为停站状态 (maxSpeed == 0) 的矿车
            if ("BLOCK_BASED".equalsIgnoreCase(plugin.getConfigFacade().getSpeedControlMode())) {
                org.bukkit.block.Block blockBelow = to.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
                String blockTypeName = blockBelow.getType().name();
                java.util.Map<String, java.util.Map<String, Double>> allWorldsMap = plugin.getConfigFacade().getBlockSpeedMap();
                
                String worldName = to.getWorld().getName();
                java.util.Map<String, Double> speedMap = allWorldsMap.get(worldName);
                if (speedMap == null || speedMap.isEmpty()) {
                    speedMap = allWorldsMap.get("default");
                }
                
                if (speedMap != null) {
                    if (speedMap.containsKey(blockTypeName)) {
                        minecart.setMaxSpeed(0.4 * speedMap.get(blockTypeName));
                    } else if (speedMap.containsKey("DEFAULT")) {
                        minecart.setMaxSpeed(0.4 * speedMap.get("DEFAULT"));
                    } else {
                        minecart.setMaxSpeed(0.4);
                    }
                } else {
                    minecart.setMaxSpeed(0.4);
                }
            }

            // ---- 传送门检测 ----
            if (plugin.getConfigFacade().isPortalsEnabled() && plugin.getPortalManager() != null) {
                String triggerBlockType = plugin.getConfigFacade().getPortalTriggerBlock();
                org.bukkit.block.Block blockBelow = to.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
                org.bukkit.block.Block blockBelow2 = blockBelow.getRelative(org.bukkit.block.BlockFace.DOWN);
                
                boolean isTriggered = false;
                org.bukkit.block.Block matchedBlock = null;

                // 矿车的坐标可能飘忽不定，如果 to.getBlock() 正好是铁轨，则 blockBelow 是支撑方块。
                if (blockBelow.getType().name().equals(triggerBlockType)) {
                    isTriggered = true;
                } else if (blockBelow2.getType().name().equals(triggerBlockType)) {
                    // 如果 to.getBlock() 是铁轨上方的空气方块，则 blockBelow 是铁轨，blockBelow2 是支撑方块。
                    isTriggered = true;
                } else if (to.getBlock().getType().name().equals(triggerBlockType)) {
                    // 防呆：有时候玩家直接把铁轨铺在非常特殊的高度上。
                    isTriggered = true;
                }

                if (isTriggered) {
                    Portal portal = plugin.getPortalManager().getPortalAt(to);
                    if (portal == null) {
                        // 尝试往下偏移一格检测（有时玩家脚踩位置低于/高于实际判定中心）
                        portal = plugin.getPortalManager().getPortalAt(to.clone().subtract(0, 1, 0));
                    }
                    if (portal != null) {
                        if (isPortalEnabledForCurrentLine(minecart, portal)) {
                            plugin.getPortalManager().teleportMinecart(minecart, portal);
                            return; // 传送后不再处理后续逻辑
                        }
                    } else {
                        plugin.getLogger().warning("[Debug-Portal] 矿车经过了触发方块 (" + triggerBlockType + ")，但该坐标 " + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ() + " 并没有绑定任何传送门入口！请重新站在上面执行 /rail portal create");
                    }
                }
            }

            // 限制上坡速度为0.4，防止到达坡顶后倒退
            if (minecart.getMaxSpeed() > 0.0 && to.getY() > from.getY()) {
                Vector direction = LocationUtil.getDirectionVector(from, to);
                minecart.setVelocity(direction.multiply(0.4));
            }
        } else {
            // 矿车已脱轨，强制乘客下车并移除矿车
            minecart.eject();
            minecart.remove();
        }
    }

    /**
     * safe_mode.damage_protection：阻止其他实体攻击/破坏地铁矿车
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMetroMinecartDamage(VehicleDamageEvent event) {
        if (!plugin.getConfigFacade().isSafeModeDamageProtection()) {
            return;
        }
        Vehicle vehicle = event.getVehicle();
        if (!(vehicle instanceof Minecart minecart)) {
            return;
        }
        if (!isRailwayTrainMinecart(minecart)) {
            return;
        }
        // 阻止任何来源对地铁矿车造成伤害（包括玩家攻击）
        event.setDamage(0);
        event.setCancelled(true);
    }

    /**
     * safe_mode.damage_protection：阻止地铁矿车被直接销毁
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMetroMinecartDestroy(VehicleDestroyEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (!(vehicle instanceof Minecart minecart)) {
            return;
        }
        TrainInstance train = findServiceTrain(minecart);
        if (plugin.getConfigFacade().isSafeModeDamageProtection()) {
            if (!isRailwayTrainMinecart(minecart)) {
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (train != null) {
            train.getService().handleTrainDerail(train);
        }
    }

    /**
     * safe_mode.entity_push_protection：阻止其他实体与地铁矿车发生物理碰撞
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMetroMinecartCollision(VehicleEntityCollisionEvent event) {
        if (!plugin.getConfigFacade().isSafeModeEntityPushProtection()) {
            return;
        }
        Vehicle vehicle = event.getVehicle();
        if (!(vehicle instanceof Minecart minecart)) {
            return;
        }
        if (!isRailwayTrainMinecart(minecart)) {
            return;
        }
        if (minecart.getPassengers().contains(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        event.setCollisionCancelled(true);
        event.setPickupCancelled(true);
    }

    /**
     * safe_mode.entity_push_protection：
     * EntityDamageByEntityEvent 覆盖 VehicleDamageEvent 未捕获的远程/投射伤害场景
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityHitMetroMinecart(EntityDamageByEntityEvent event) {
        if (!plugin.getConfigFacade().isSafeModeEntityPushProtection()) {
            return;
        }
        Entity damaged = event.getEntity();
        if (!(damaged instanceof Minecart minecart)) {
            return;
        }
        if (!isRailwayTrainMinecart(minecart)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * 检查位置是否在任何停靠区内
     */
    private boolean isAtStop(Location location) {
        StopManager stopManager = plugin.getStopManager();
        return stopManager.getStopContainingLocation(location) != null;
    }

    private boolean isMetroMinecart(Minecart minecart) {
        return minecart.getPersistentDataContainer().has(MetroConstants.getMinecartKey(), PersistentDataType.BYTE);
    }

    private boolean isRailwayTrainMinecart(Minecart minecart) {
        return isMetroMinecart(minecart) || findServiceTrain(minecart) != null;
    }

    private void handleServiceTrainMove(TrainInstance train, Minecart minecart, Location to) {
        if (!train.isLead(minecart)) {
            return;
        }

        if (!LocationUtil.isOnRail(to)) {
            train.getService().handleTrainDerail(train);
            return;
        }

        String targetStopId = train.getTargetStopId();
        if (targetStopId == null) {
            return;
        }

        Stop targetStop = plugin.getStopManager().getStop(targetStopId);
        if (targetStop == null) {
            return;
        }

        boolean arrived = false;
        Location stopPoint = targetStop.getStopPointLocation();
        if (stopPoint != null && stopPoint.getWorld() != null && to.getWorld() != null
                && stopPoint.getWorld().equals(to.getWorld())) {
            arrived = stopPoint.distanceSquared(to) <= 4.0;
        } else if (targetStop.isInStop(to)) {
            arrived = true;
        }

        if (arrived) {
            train.handleArrival(targetStop, SchedulerUtil.getCurrentTick());
        }
    }

    private TrainInstance findServiceTrain(Minecart minecart) {
        if (minecart == null || plugin.getLineServiceManager() == null) {
            return null;
        }
        return plugin.getLineServiceManager().getTrainByMinecart(minecart.getUniqueId());
    }

    private boolean isSuccessfulCharge(TicketService.TicketChargeStatus status) {
        return status == TicketService.TicketChargeStatus.CHARGED
                || status == TicketService.TicketChargeStatus.FREE
                || status == TicketService.TicketChargeStatus.ECONOMY_DISABLED;
    }

    private void sendTicketCheckFailure(Player player, TicketService.TicketCheck ticketCheck) {
        if (ticketCheck.getStatus() == TicketService.TicketCheckStatus.INSUFFICIENT_FUNDS) {
            player.sendMessage(plugin.getLanguageManager().getMessage("economy.insufficient_funds",
                    LanguageManager.put(LanguageManager.args(), "price", ticketCheck.getFormattedPrice())));
            return;
        }
        if (ticketCheck.getStatus() == TicketService.TicketCheckStatus.VAULT_UNAVAILABLE) {
            player.sendMessage(plugin.getLanguageManager().getMessage("economy.vault_unavailable"));
            return;
        }
        player.sendMessage(plugin.getLanguageManager().getMessage("economy.transaction_failed"));
    }

    private void sendTicketChargeFailure(Player player, TicketService.TicketChargeStatus status,
            TicketService.TicketTransaction transaction) {
        if (status == TicketService.TicketChargeStatus.INSUFFICIENT_FUNDS) {
            player.sendMessage(plugin.getLanguageManager().getMessage("economy.insufficient_funds",
                    LanguageManager.put(LanguageManager.args(), "price",
                            plugin.getTicketService().format(transaction.getPrice()))));
            return;
        }
        if (status == TicketService.TicketChargeStatus.VAULT_UNAVAILABLE) {
            player.sendMessage(plugin.getLanguageManager().getMessage("economy.vault_unavailable"));
            return;
        }
        player.sendMessage(plugin.getLanguageManager().getMessage("economy.transaction_failed"));
    }

    private boolean isPortalEnabledForCurrentLine(Minecart minecart, Portal portal) {
        if (portal == null) {
            return false;
        }
        TrainMovementTask task = TrainMovementTask.getTaskFor(minecart);
        return task != null && task.canUsePortal(portal.getId());
    }
}
