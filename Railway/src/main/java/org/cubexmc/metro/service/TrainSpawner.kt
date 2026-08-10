package org.cubexmc.metro.service

import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.service.virtual.SpawnMode
import org.cubexmc.metro.train.TrainConsist
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.LocationUtil
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Handles physical train spawning for a line service. */
class TrainSpawner(
    private val plugin: Metro,
    private val service: LineService,
) {
    fun spawnTrainAtFirstStop(currentTick: Long): Boolean {
        val line = service.line
        val lineId = service.lineId
        if (line == null) {
            plugin.logger.warning("Line $lineId not found when spawning train.")
            return false
        }
        val stops = line.orderedStopIds
        if (stops.size < 2) {
            plugin.logger.warning("Line $lineId needs at least two stops to spawn trains.")
            return false
        }
        val startStop = plugin.stopManager.getStop(stops[0])
        if (startStop?.stopPointLocation == null) {
            plugin.logger.warning("Start stop for line $lineId is not configured with a stop point.")
            return false
        }
        val consist = spawnConsist(line, startStop)
        if (consist.getCars().isEmpty()) {
            plugin.logger.warning("Failed to spawn minecart consist for line $lineId.")
            return false
        }
        val train = TrainInstance(service, line, consist, stops, currentTick, service.dwellTicks)
        service.addTrain(train, currentTick)
        return true
    }

    fun spawnTrainForVirtual(
        currentTick: Long,
        fromStopIndex: Int,
        toStopIndex: Int,
        progress: Double,
        virtualTrainId: UUID,
        targetStopId: String,
    ): TrainInstance? {
        val line = service.line ?: return null
        val lineId = service.lineId
        val stops = line.orderedStopIds
        if (stops.size < 2) {
            return null
        }
        val mode = SpawnMode.from(plugin.localSpawnMode, SpawnMode.CURRENT_STOP) ?: SpawnMode.CURRENT_STOP
        var spawnLoc: Location? = null
        var yaw = 0f
        var effectiveFromIndex = fromStopIndex

        when (mode) {
            SpawnMode.MID_SEGMENT -> {
                spawnLoc = findMidSegmentSpawnLocation(stops, fromStopIndex, toStopIndex, progress)
                if (spawnLoc != null) {
                    yaw = computeYawBetweenStops(stops, fromStopIndex)
                } else {
                    val fromStop = stopAt(stops, fromStopIndex)
                    val stopPoint = fromStop?.stopPointLocation
                    if (stopPoint != null) {
                        spawnLoc = LocationUtil.center(stopPoint.clone())
                        yaw = fromStop.launchYaw
                        effectiveFromIndex = fromStopIndex
                    }
                }
            }

            SpawnMode.PREVIOUS_STOP -> {
                val fromStop = stopAt(stops, fromStopIndex)
                val stopPoint = fromStop?.stopPointLocation
                if (stopPoint != null) {
                    spawnLoc = LocationUtil.center(stopPoint.clone())
                    yaw = fromStop.launchYaw
                    effectiveFromIndex = fromStopIndex
                }
            }

            SpawnMode.PLATFORM_BOUNDARY -> {
                spawnLoc = findPlatformBoundarySpawnLocation(stops, targetStopId, fromStopIndex)
                if (spawnLoc != null) {
                    yaw = computeYawTowardsStop(targetStopId)
                } else {
                    val fallbackStop = stopAt(stops, fromStopIndex)
                    val stopPoint = fallbackStop?.stopPointLocation
                    if (stopPoint != null) {
                        spawnLoc = LocationUtil.center(stopPoint.clone())
                        yaw = fallbackStop.launchYaw
                    }
                }
            }

            SpawnMode.CURRENT_STOP -> {
                val targetStop = plugin.stopManager.getStop(targetStopId)
                val stopPoint = targetStop?.stopPointLocation
                if (stopPoint != null) {
                    spawnLoc = LocationUtil.center(stopPoint.clone())
                    yaw = targetStop.launchYaw
                    effectiveFromIndex = stops.indexOf(targetStopId).coerceAtLeast(0)
                }
            }
        }

        if (spawnLoc == null) {
            plugin.logger.warning("Failed to find spawn location for line $lineId in mode $mode")
            return null
        }
        var railLoc = findNearestRail(spawnLoc, plugin.localRailSearchRadius)
        if (railLoc == null) {
            plugin.logger.warning("No rail found near spawn location for line $lineId at $spawnLoc")
            val firstStop = plugin.stopManager.getStop(stops[0])
            val firstStopPoint = firstStop?.stopPointLocation
            if (firstStopPoint == null) {
                return null
            }
            railLoc = LocationUtil.center(firstStopPoint.clone())
            yaw = firstStop.launchYaw
            effectiveFromIndex = 0
        } else {
            railLoc = LocationUtil.center(railLoc)
        }

        val consist = spawnConsistAt(railLoc ?: return null, yaw)
        if (consist.getCars().isEmpty()) {
            plugin.logger.warning("Failed to spawn minecart consist for line $lineId")
            return null
        }
        val train = TrainInstance(service, line, consist, stops, currentTick, service.dwellTicks)
        train.virtualTrainId = virtualTrainId

        if (mode == SpawnMode.CURRENT_STOP) {
            train.forceWaitingState(effectiveFromIndex, currentTick)
        } else if (mode == SpawnMode.PLATFORM_BOUNDARY && fromStopIndex == toStopIndex) {
            val targetStop = plugin.stopManager.getStop(targetStopId)
            val targetPoint = targetStop?.stopPointLocation
            if (targetPoint != null) {
                val spawnVec = train.consist.getLeadCar()?.location?.toVector()
                if (spawnVec == null) {
                    train.forceArrivingState(effectiveFromIndex, currentTick)
                } else {
                    val distinctDir = targetPoint.toVector().subtract(spawnVec)
                    if (distinctDir.lengthSquared() > 0.001) {
                        distinctDir.normalize()
                        train.forceArrivingState(effectiveFromIndex, currentTick, distinctDir)
                        val lead = consist.getLeadCar()
                        if (lead != null && !lead.isDead) {
                            lead.velocity = distinctDir.clone().multiply(service.cartSpeed)
                        }
                    } else {
                        train.forceArrivingState(effectiveFromIndex, currentTick)
                    }
                }
            } else {
                train.forceArrivingState(effectiveFromIndex, currentTick)
            }
        } else {
            train.adjustStartIndex(effectiveFromIndex, currentTick)
        }
        service.addTrain(train, currentTick)
        return train
    }

    private fun spawnConsist(line: Line, startStop: Stop): TrainConsist {
        val stopPoint = startStop.stopPointLocation ?: return TrainConsist()
        val basePoint = LocationUtil.center(stopPoint.clone()) ?: throw NullPointerException("location")
        return spawnConsistAt(basePoint, startStop.launchYaw)
    }

    private fun spawnConsistAt(initialBasePoint: Location, yaw: Float): TrainConsist {
        val consist = TrainConsist()
        var direction = vectorFromYawOrNull(yaw)
        if (direction == null || direction.lengthSquared() == 0.0) {
            direction = Vector(0, 0, 1)
        }
        val railSearchRadius = max(2, plugin.localRailSearchRadius)
        val basePoint = findNearestRail(initialBasePoint, railSearchRadius) ?: initialBasePoint
        val railDirection = LocationUtil.railDirection(basePoint, direction)
        if (railDirection != null && railDirection.lengthSquared() > 1.0e-6) {
            railDirection.y = 0.0
            if (railDirection.lengthSquared() > 1.0e-6) {
                direction = railDirection.normalize()
            }
        }
        val spacing = service.trainSpacing
        val maxSpeed = service.cartSpeed
        val trainCars = service.trainCars
        for (index in 0 until trainCars) {
            val idealLoc = basePoint.clone().subtract(direction.clone().multiply(index * spacing))
            idealLoc.yaw = yaw
            var spawnLoc = findNearestRail(idealLoc, railSearchRadius) ?: idealLoc
            spawnLoc.yaw = yaw
            if (!LocationUtil.isRail(spawnLoc)) {
                val nearRail = findNearestRail(spawnLoc, railSearchRadius)
                if (nearRail == null) {
                    plugin.logger.warning("Spawn location for train car is not on rail at $spawnLoc")
                    cleanupIncompleteConsist(consist)
                    return consist
                }
                spawnLoc = nearRail
                spawnLoc.yaw = yaw
            }
            val world = spawnLoc.world ?: throw NullPointerException("world")
            val cart = world.spawn(spawnLoc, Minecart::class.java) { minecart ->
                minecart.customName = plugin.trainName
                minecart.isCustomNameVisible = plugin.isTrainNameVisible
                minecart.isSlowWhenEmpty = false
                minecart.maxSpeed = maxSpeed
                minecart.setGravity(false)
            }
            consist.addCar(cart)
        }
        if (consist.getCars().size != trainCars) {
            cleanupIncompleteConsist(consist)
        }
        return consist
    }

    private fun findMidSegmentSpawnLocation(
        stops: List<String>,
        fromIndex: Int,
        toIndex: Int,
        progress: Double,
    ): Location? {
        if (fromIndex !in stops.indices || toIndex !in stops.indices) {
            return null
        }
        val fromStop = plugin.stopManager.getStop(stops[fromIndex]) ?: return null
        val toStop = plugin.stopManager.getStop(stops[toIndex]) ?: return null
        val from = fromStop.stopPointLocation ?: return null
        val to = toStop.stopPointLocation ?: return null
        val world = from.world ?: return null
        if (world != to.world) {
            return null
        }
        val progressClamped = max(0.0, min(1.0, progress))
        val interpolated = Location(
            world,
            from.x + (to.x - from.x) * progressClamped,
            from.y + (to.y - from.y) * progressClamped,
            from.z + (to.z - from.z) * progressClamped,
        )
        return findNearestRail(interpolated, plugin.localRailSearchRadius)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun findPlatformBoundarySpawnLocation(
        stops: List<String>,
        targetStopId: String,
        fromIndex: Int,
    ): Location? {
        val targetStop = plugin.stopManager.getStop(targetStopId) ?: return null
        val stopPoint = targetStop.stopPointLocation ?: return null
        var launchDir = vectorFromYawOrNull(targetStop.launchYaw)
        if (launchDir == null || launchDir.lengthSquared() < 1.0e-6) {
            launchDir = Vector(1, 0, 0)
        } else {
            launchDir = launchDir.normalize()
        }
        val corner1 = targetStop.corner1
        val corner2 = targetStop.corner2
        if (corner1 != null && corner2 != null && corner1.world != null && corner1.world == corner2.world) {
            val minX = min(corner1.x, corner2.x)
            val maxX = max(corner1.x, corner2.x)
            val minZ = min(corner1.z, corner2.z)
            val maxZ = max(corner1.z, corner2.z)
            val entryPoint = stopPoint.clone()
            if (abs(launchDir.x) > abs(launchDir.z)) {
                entryPoint.x = if (launchDir.x > 0) minX - 3.0 else maxX + 3.0
            } else {
                entryPoint.z = if (launchDir.z > 0.0) minZ - 3.0 else maxZ + 3.0
            }
            return findNearestRail(entryPoint, plugin.localRailSearchRadius + 3)
        }
        val approxEntry = stopPoint.clone().subtract(launchDir.multiply(20.0))
        return findNearestRail(approxEntry, plugin.localRailSearchRadius)
    }

    private fun computeYawBetweenStops(stops: List<String>, fromIndex: Int): Float {
        if (fromIndex !in stops.indices) {
            return 0f
        }
        return stopAt(stops, fromIndex)?.launchYaw ?: 0f
    }

    private fun computeYawTowardsStop(toStopId: String): Float =
        plugin.stopManager.getStop(toStopId)?.launchYaw ?: 0f

    private fun stopAt(stops: List<String>, index: Int): Stop? =
        plugin.stopManager.getStop(stops[index])

    private fun findNearestRail(center: Location, radius: Int): Location? =
        LocationUtil.findNearestRail(center, max(0, radius).toDouble())

    private fun vectorFromYawOrNull(yaw: Float): Vector? = LocationUtil.vectorFromYaw(yaw)

    private fun cleanupIncompleteConsist(consist: TrainConsist) {
        for (cart in consist.getCars()) {
            if (!cart.isDead) {
                cart.remove()
            }
        }
        consist.clear()
    }
}
