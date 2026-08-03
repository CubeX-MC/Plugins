package org.cubexmc.metro.train

import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.util.LocationUtil
import org.cubexmc.metro.util.MinecartPhysicsUtil

class TrainNavigator(
    private val train: TrainInstance,
    val stopIds: List<String>,
) {
    var currentIndex: Int = 0
    var targetIndex: Int = -1
    var sectionKey: String? = null
    var travelDirection: Vector? = null

    fun attemptDeparture(currentTick: Long) {
        val departure = TrainNavigatorDecisions.resolveDeparture(stopIds.size, currentIndex, targetIndex)
        if (departure.shouldTerminate) {
            beginTermination(currentTick)
            return
        }
        targetIndex = departure.targetIndex

        val fromId = stopIds[currentIndex]
        val toId = stopIds[targetIndex]

        val fromStop = train.service.stopManager.getStop(fromId)
        val toStop = train.service.stopManager.getStop(toId)
        if (fromStop == null || toStop == null || toStop.stopPointLocation == null) {
            beginTermination(currentTick)
            return
        }

        val section = train.service.buildSectionKey(fromId, toId)
        if (!train.service.blockSectionManager.tryEnter(section)) {
            // Section already occupied -- another train (or a leaked reservation)
            // is holding it. Surface this to debug so a future "stuck train"
            // report can be diagnosed without spelunking.
            train.service.plugin.debug(
                "train_state_transitions",
                "Departure blocked: section '$section' is occupied; train " +
                    "${train.id} staying in WAITING at $fromId",
            )
            return
        }
        sectionKey = section

        var boostDir: Vector? = LocationUtil.vectorFromYaw(fromStop.launchYaw)
        if (boostDir == null || boostDir.lengthSquared() < 1e-6) {
            boostDir = Vector(0, 0, 1)
        }

        travelDirection = boostDir.normalize()

        train.applyInitialBoost(travelDirection)
        train.physicsEngine.onDeparture(train, fromStop)
        train.publishDepartureEvents(fromStop, toStop)
        train.setState(TrainInstance.TrainState.MOVING, currentTick)
        train.setReadyToDepart(false)
        train.onSegmentStart(currentTick)
    }

    fun checkArrival(currentTick: Long) {
        if (targetIndex < 0 || targetIndex >= stopIds.size) {
            return
        }

        val targetStop = train.service.stopManager.getStop(stopIds[targetIndex])
        val stopPointLocation = targetStop?.stopPointLocation ?: return

        val lead = train.consist.getLeadCar()
        if (lead == null || lead.isDead) {
            return
        }

        val distSq = lead.location.distanceSquared(stopPointLocation)
        val leadSpeed = lead.velocity.length()

        if (ArrivalHeuristics.shouldArrive(distSq, leadSpeed)) {
            arriveAtStop(targetStop, currentTick)
        }
    }

    private fun arriveAtStop(stop: Stop, currentTick: Long) {
        val arrival = TrainNavigatorDecisions.resolveArrival(
            stopIds,
            targetIndex,
            stop.id,
            train.service.isLoopLine,
        )
        if (!arrival.valid) {
            return
        }

        // Release the block-section reservation for the segment we just finished.
        // Without this the section stays "occupied" in BlockSectionManager forever
        // and any later train (or the same train re-traversing the segment on a
        // loop line) will fail tryEnter() on departure -- the symptom being
        // a freshly spawned train that sits at the platform and never leaves.
        // The parallel handleArrival/beginTermination/cleanup paths all release
        // here; this one was the only auto-arrival path missing it.
        val occupiedSection = sectionKey
        if (occupiedSection != null) {
            train.service.blockSectionManager.leave(occupiedSection)
            sectionKey = null
        }

        train.setState(arrival.nextState, currentTick)
        currentIndex = arrival.currentIndex
        targetIndex = arrival.targetIndex
        train.setReadyToDepart(false)

        train.consist.zeroVelocity()
        travelDirection = null

        val stopPointLocation = stop.stopPointLocation
        if (stopPointLocation != null) {
            val lead = train.consist.getLeadCar()
            if (lead != null && !lead.isDead) {
                val yaw = lead.location.yaw
                val snapLoc = stopPointLocation.clone()
                snapLoc.yaw = yaw
                snapLoc.pitch = lead.location.pitch
                lead.teleport(snapLoc)
                MinecartPhysicsUtil.forceVelocity(lead, Vector(0, 0, 0), train.service.plugin)
            }
        }

        train.physicsEngine.onArrival(train, stop, currentTick)
        train.publishArrivalEvents(stop, arrival.terminal)

        train.tryRecordTravelTimeSample(currentTick)
    }

    fun handleArrival(stop: Stop, currentTick: Long) {
        if (!train.isMoving) {
            return
        }

        val targetId = getTargetStopId()
        if (targetId == null || targetId != stop.id) {
            return
        }

        train.consist.zeroVelocity()
        val occupiedSection = sectionKey
        if (occupiedSection != null) {
            train.service.blockSectionManager.leave(occupiedSection)
            sectionKey = null
        }
        travelDirection = null
        val arrival = TrainNavigatorDecisions.resolveArrival(
            stopIds,
            targetIndex,
            stop.id,
            train.service.isLoopLine,
        )
        if (!arrival.valid) {
            return
        }

        train.setState(arrival.nextState, currentTick)
        train.setReadyToDepart(false)
        currentIndex = arrival.currentIndex
        targetIndex = arrival.targetIndex

        train.physicsEngine.onArrival(train, stop, currentTick)
        train.publishArrivalEvents(stop, arrival.terminal)

        train.tryRecordTravelTimeSample(currentTick)
    }

    fun beginTermination(currentTick: Long) {
        val occupiedSection = sectionKey
        if (occupiedSection != null) {
            train.service.blockSectionManager.leave(occupiedSection)
            sectionKey = null
        }
        travelDirection = null
        train.setState(TrainInstance.TrainState.TERMINATING, currentTick)
    }

    fun cleanup() {
        val occupiedSection = sectionKey
        if (occupiedSection != null) {
            train.service.blockSectionManager.leave(occupiedSection)
            sectionKey = null
        }
    }

    fun getTargetStopId(): String? =
        if (targetIndex >= 0 && targetIndex < stopIds.size) stopIds[targetIndex] else null
}
