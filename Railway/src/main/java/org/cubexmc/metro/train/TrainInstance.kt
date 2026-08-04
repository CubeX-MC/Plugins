package org.cubexmc.metro.train

import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.control.TrainControlMode
import org.cubexmc.metro.estimation.TravelTimeEstimator
import org.cubexmc.metro.event.MetroTrainArrivalEvent
import org.cubexmc.metro.event.MetroTrainDepartureEvent
import org.cubexmc.metro.model.EntityModelController
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.physics.KinematicRailPhysics
import org.cubexmc.metro.physics.LeashedRailPhysics
import org.cubexmc.metro.physics.ReactiveRailPhysics
import org.cubexmc.metro.physics.TrainPhysicsEngine
import org.cubexmc.metro.service.LineService
import org.cubexmc.metro.util.LocationUtil
import org.cubexmc.metro.util.SchedulerUtil
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class TrainInstance(
    service: LineService?,
    line: Line?,
    consist: TrainConsist?,
    orderedStopIds: List<String>?,
    spawnTick: Long,
    dwellTicks: Int,
) {
    enum class TrainState {
        WAITING,
        MOVING,
        TERMINATING,
        FINISHED,
    }

    val id: UUID = UUID.randomUUID()
    val service: LineService = service ?: throw NullPointerException("service")
    val line: Line = line ?: throw NullPointerException("line")
    val consist: TrainConsist = consist ?: throw NullPointerException("consist")
    val navigator: TrainNavigator
    private val key: NamespacedKey

    private var state: TrainState
    private var stateSinceTick: Long
    private var cleaned = false
    private val dwellTicks: Int
    private var readyToDepart = false
    private var segmentStartTick = -1L
    private val segmentDepartPassengers = HashSet<UUID>()
    private var idleVirtualTicks = 0L
    private var stalledTicks = 0

    private val passengerRegistry = TrainPassengerRegistry()
    lateinit var physicsEngine: TrainPhysicsEngine
        private set

    private val forcedChunks = HashSet<Long>()
    private var lastChunkUpdateTick = 0L

    var virtualTrainId: UUID? = null

    init {
        val stopIdsList = ArrayList(orderedStopIds ?: throw NullPointerException("orderedStopIds"))
        if (stopIdsList.size < 2) {
            throw IllegalArgumentException("orderedStopIds must contain at least two stops")
        }
        navigator = TrainNavigator(this, stopIdsList)

        key = this.service.trainKey
        tagMinecarts()
        state = TrainState.WAITING
        stateSinceTick = spawnTick
        this.dwellTicks = max(20, dwellTicks)
        physicsEngine = selectEngine()
        physicsEngine.init(this)
        attachEntityModels()
    }

    private fun tagMinecarts() {
        for (cart in consist.getCars()) {
            cart.persistentDataContainer.set(key, PersistentDataType.STRING, id.toString())
        }
    }

    fun refreshPhysicsEngine() {
        if (cleaned) {
            return
        }
        physicsEngine.cleanup(this)
        physicsEngine = selectEngine()
        physicsEngine.init(this)
    }

    fun refreshEntityModels() {
        if (cleaned) {
            return
        }
        for (cart in consist.getCars()) {
            removeEntityModel(cart)
        }
        attachEntityModels()
    }

    fun getTargetStopId(): String? = navigator.getTargetStopId()

    fun setState(state: TrainState, tick: Long) {
        this.state = state
        stateSinceTick = tick
    }

    fun setReadyToDepart(ready: Boolean) {
        readyToDepart = ready
    }

    fun onSegmentStart(tick: Long) {
        segmentStartTick = tick
        segmentDepartPassengers.clear()
        segmentDepartPassengers.addAll(passengerRegistry.snapshotPassengerIds())
    }

    val isFinished: Boolean
        get() = state == TrainState.FINISHED

    val isWaiting: Boolean
        get() = state == TrainState.WAITING

    val isMoving: Boolean
        get() = state == TrainState.MOVING

    fun update(currentTick: Long) {
        if (state != TrainState.MOVING) {
            idleVirtualTicks = 0L
        }
        when (state) {
            TrainState.WAITING -> {
                consist.zeroVelocity()
                if (!readyToDepart && currentTick - stateSinceTick >= dwellTicks) {
                    readyToDepart = true
                }
                if (readyToDepart) {
                    navigator.attemptDeparture(currentTick)
                } else {
                    // Passenger-facing waiting UI is driven by MetroTrainArrivalEvent(DOCKED),
                    // matching the legacy Metro display pipeline.
                }
            }

            TrainState.MOVING -> {
                navigator.checkArrival(currentTick)
                if (state == TrainState.MOVING) {
                    val leadSpeed = getLeadSpeed()
                    val subSteps = computeSubSteps(leadSpeed)
                    val stepFraction = if (subSteps <= 1) 1.0 else 1.0 / subSteps
                    repeat(subSteps) {
                        maintainVelocity(stepFraction, leadSpeed)
                    }
                    if (leadSpeed < 1.0e-3) {
                        stalledTicks++
                        if (stalledTicks >= 4) {
                            val lead = consist.getLeadCar()
                            if (lead != null && !lead.isDead) {
                                val travelDirection = navigator.travelDirection
                                val direction =
                                    if (travelDirection != null && travelDirection.lengthSquared() > 0) {
                                        travelDirection.clone().normalize()
                                    } else {
                                        Vector(1, 0, 0)
                                    }
                                val velocity = direction.multiply(max(0.1, service.cartSpeed * 0.5))
                                if (SchedulerUtil.isFolia()) {
                                    SchedulerUtil.entityRun(
                                        service.plugin,
                                        lead,
                                        Runnable { lead.velocity = velocity },
                                        0L,
                                        -1L,
                                    )
                                } else {
                                    lead.velocity = velocity
                                }
                            }
                            stalledTicks = 0
                        }
                    } else {
                        stalledTicks = 0
                    }
                    maybeVirtualize(currentTick)
                    // Passenger-facing journey UI is driven by MetroTrainDepartureEvent,
                    // matching the legacy Metro display pipeline.
                }
            }

            TrainState.TERMINATING -> {
                consist.zeroVelocity()
                if (currentTick - stateSinceTick >= dwellTicks) {
                    finish()
                }
            }

            TrainState.FINISHED -> Unit
        }

        if (service.plugin.isChunkLoadingEnabled) {
            updateChunkLoading(currentTick)
        }

        syncEntityModels()
    }

    private fun getLeadSpeed(): Double {
        val lead = consist.getLeadCar()
        if (lead == null || lead.isDead) {
            return 0.0
        }
        return lead.velocity.length()
    }

    private fun computeSubSteps(leadSpeed: Double): Int {
        val maxSubSteps = 12
        if (leadSpeed <= 0.35) {
            return 1
        }
        val steps = ceil(leadSpeed / 0.35).toInt()
        return max(1, min(steps, maxSubSteps))
    }

    private fun maintainVelocity(timeFraction: Double, leadSpeed: Double) {
        val lead = consist.getLeadCar()
        if (lead == null || lead.isDead) {
            return
        }
        physicsEngine.tick(this, timeFraction, -1L)
    }

    private fun hasAnyPassengers(): Boolean {
        if (passengerRegistry.hasOnlinePassengers()) {
            return true
        }
        for (cart in consist.getCars()) {
            if (cart.passengers.isNotEmpty()) {
                return true
            }
        }
        return false
    }

    private fun maybeVirtualize(currentTick: Long) {
        if (service.isGlobalMode) {
            idleVirtualTicks = 0L
            return
        }

        val plugin = service.plugin
        if (!plugin.isLocalVirtualizationEnabled) {
            idleVirtualTicks = 0L
            return
        }

        if (hasAnyPassengers()) {
            idleVirtualTicks = 0L
            return
        }

        if (navigator.targetIndex < 0 || navigator.targetIndex >= navigator.stopIds.size) {
            idleVirtualTicks = 0L
            return
        }

        val radius = plugin.localActivationRadius
        val radiusSquared = radius * radius
        val lookahead = plugin.localVirtualLookaheadStops

        val stopManager = service.stopManager
        var demandAhead = false
        var offset = 0
        while (offset < lookahead && navigator.targetIndex + offset < navigator.stopIds.size) {
            val candidate = stopManager.getStop(navigator.stopIds[navigator.targetIndex + offset])
            if (candidate != null && plugin.isPlayerWithinStopRadius(candidate, radiusSquared)) {
                demandAhead = true
                break
            }
            offset++
        }

        if (demandAhead) {
            idleVirtualTicks = 0L
            return
        }

        idleVirtualTicks++
        if (idleVirtualTicks >= plugin.localVirtualIdleTicks) {
            service.virtualizeBackToPool(this, currentTick)
            service.markVirtualizedTrain(currentTick)
            finishImmediately()
        }
    }

    fun applyInitialBoost(direction: Vector?) {
        var launchDirection = direction?.clone() ?: Vector(0, 0, 1)
        if (launchDirection.lengthSquared() == 0.0) {
            launchDirection = Vector(0, 0, 1)
        }
        launchDirection.normalize()

        val initialSpeed = service.cartSpeed
        for (cart in consist.getCars()) {
            if (cart.isDead) {
                continue
            }
            val velocity = launchDirection.clone().multiply(initialSpeed)
            if (SchedulerUtil.isFolia()) {
                SchedulerUtil.entityRun(
                    service.plugin,
                    cart,
                    Runnable { cart.velocity = velocity },
                    0L,
                    -1L,
                )
            } else {
                cart.velocity = velocity
            }
        }
    }

    fun handleArrival(stop: Stop?, currentTick: Long) {
        if (state != TrainState.MOVING) {
            return
        }
        val arrivedStop = stop ?: throw NullPointerException("stop")

        val targetId = navigator.getTargetStopId()
        if (targetId == null || targetId != arrivedStop.id) {
            return
        }

        consist.zeroVelocity()
        val occupiedSection = navigator.sectionKey
        if (occupiedSection != null) {
            service.blockSectionManager.leave(occupiedSection)
            navigator.sectionKey = null
        }
        navigator.travelDirection = null
        val arrival =
            TrainNavigatorDecisions.resolveArrival(
                navigator.stopIds,
                navigator.targetIndex,
                arrivedStop.id,
                service.isLoopLine,
            )
        if (!arrival.valid) {
            return
        }

        state = arrival.nextState
        stateSinceTick = currentTick
        readyToDepart = false
        navigator.currentIndex = arrival.currentIndex
        navigator.targetIndex = arrival.targetIndex

        physicsEngine.onArrival(this, arrivedStop, currentTick)
        publishArrivalEvents(arrivedStop, arrival.terminal)
        tryRecordTravelTimeSample(currentTick)
    }

    private fun finish() {
        consist.zeroVelocity()
        releaseRoutingReservation()
        releaseAllForcedChunks()
        state = TrainState.FINISHED
    }

    fun finishImmediately() {
        consist.zeroVelocity()
        releaseRoutingReservation()
        releaseAllForcedChunks()
        state = TrainState.FINISHED
    }

    fun cleanup() {
        if (cleaned) {
            return
        }
        cleaned = true
        physicsEngine.cleanup(this)
        consist.zeroVelocity()
        releaseRoutingReservation()
        releaseAllForcedChunks()

        for (cart in consist.getCars()) {
            cart.persistentDataContainer.remove(key)
            removeEntityModel(cart)
            if (!cart.isDead) {
                for (passenger in ArrayList(cart.passengers)) {
                    if (passenger is Player) {
                        service.plugin.scoreboardManager.clearPlayerDisplay(passenger)
                    }
                    passenger.leaveVehicle()
                }
                cart.remove()
            }
        }
        resetTransientRuntimeState()
        consist.clear()
    }

    fun getCurrentFromStopId(): String? {
        if (
            navigator.targetIndex >= 0 &&
            navigator.currentIndex >= 0 &&
            navigator.currentIndex < navigator.stopIds.size
        ) {
            return navigator.stopIds[navigator.currentIndex]
        }
        return null
    }

    fun getCurrentToStopId(): String? {
        if (navigator.targetIndex >= 0 && navigator.targetIndex < navigator.stopIds.size) {
            return navigator.stopIds[navigator.targetIndex]
        }
        return null
    }

    fun getSegmentElapsedSeconds(currentTick: Long): Double =
        if (state == TrainState.MOVING) {
            max(0.0, (currentTick - stateSinceTick) / 20.0)
        } else {
            0.0
        }

    fun estimateEtaSecondsToStop(
        stopId: String?,
        currentTick: Long,
        estimator: TravelTimeEstimator,
    ): Double =
        TrainStateMath.estimateEtaSecondsToStop(
            state,
            navigator.stopIds,
            navigator.currentIndex,
            navigator.targetIndex,
            service.isLoopLine,
            stopId,
            getSegmentElapsedSeconds(currentTick),
            TrainStateMath.SegmentSecondsLookup { fromStopId, toStopId ->
                estimator.estimateSeconds(service.lineId, fromStopId, toStopId)
            },
        )

    fun isLead(cart: Minecart): Boolean {
        val lead = consist.getLeadCar()
        return lead != null && lead.uniqueId == cart.uniqueId
    }

    fun getTravelDirection(): Vector? = navigator.travelDirection

    private fun selectEngine(): TrainPhysicsEngine {
        val mode =
            line.controlMode
                ?: TrainControlMode.from(service.plugin.config().getControlMode(), TrainControlMode.KINEMATIC)
                ?: throw NullPointerException("control mode")
        return when (mode) {
            TrainControlMode.REACTIVE -> ReactiveRailPhysics()
            TrainControlMode.LEASHED -> LeashedRailPhysics()
            TrainControlMode.KINEMATIC -> KinematicRailPhysics()
        }
    }

    private fun attachEntityModels() {
        val plugin = service.plugin
        val controller = plugin.entityModelController ?: return
        if (!shouldUseEntityModels(plugin)) {
            return
        }
        val entityType = line.getEntityType()
        val entityTypeOverride = if (EntityModelController.usesVisualEntity(entityType)) entityType else null
        for (cart in consist.getCars()) {
            controller.attachModel(cart, entityTypeOverride)
        }
    }

    private fun syncEntityModels() {
        val controller = service.plugin.entityModelController ?: return
        for (cart in consist.getCars()) {
            controller.syncPosition(cart)
        }
    }

    private fun shouldUseEntityModels(plugin: Metro): Boolean {
        if (EntityModelController.usesVisualEntity(line.getEntityType())) {
            return true
        }
        return !line.hasEntityTypeOverride() && plugin.config.getBoolean("entity-model.enabled", false)
    }

    private fun removeEntityModel(cart: Minecart) {
        service.plugin.entityModelController?.removeModel(cart)
    }

    fun addPassenger(player: HumanEntity?, cart: Minecart?) {
        if (player != null && cart != null && consist.getCars().contains(cart)) {
            val added = passengerRegistry.add(player, cart)
            updatePassengerScoreboard(player)
            if (added && state == TrainState.WAITING) {
                publishDockedEventFor(player, cart, currentStop(), isTerminalStop())
            }
        }
    }

    fun removePassenger(player: HumanEntity?) {
        passengerRegistry.remove(player)
    }

    fun getPassengers(): List<HumanEntity> = passengerRegistry.onlinePassengers()

    fun hasPassengers(): Boolean = passengerRegistry.hasOnlinePassengers()

    fun isPassenger(player: HumanEntity?): Boolean = passengerRegistry.contains(player)

    fun getPassengerCart(player: Entity?): Minecart? {
        if (player == null || player.vehicle == null) {
            return if (player is HumanEntity) passengerRegistry.cartFor(player) else null
        }

        val vehicle = player.vehicle
        if (vehicle is Minecart && consist.getCars().contains(vehicle)) {
            return vehicle
        }

        return if (player is HumanEntity) passengerRegistry.cartFor(player) else null
    }

    fun getCurrentStopId(): String? {
        val stopIds = navigator.stopIds
        val currentIndex = navigator.currentIndex
        if (currentIndex >= 0 && currentIndex < stopIds.size) {
            return stopIds[currentIndex]
        }
        return null
    }

    fun getRemainingDwellTicks(currentTick: Long): Int {
        if (state != TrainState.WAITING) {
            return 0
        }
        val elapsed = max(0L, currentTick - stateSinceTick)
        return max(0, (dwellTicks - elapsed).toInt())
    }

    fun publishDepartureEvents(currentStop: Stop?, nextStop: Stop?) {
        if (currentStop == null || nextStop == null) {
            return
        }
        for (passenger in getPassengers()) {
            if (passenger is Player) {
                val passengerCart = passengerRegistry.cartFor(passenger) ?: consist.getLeadCar()
                Bukkit.getPluginManager().callEvent(
                    MetroTrainDepartureEvent(passengerCart, passenger, line, currentStop, nextStop),
                )
            }
        }
    }

    fun publishArrivalEvents(currentStop: Stop?, terminal: Boolean) {
        if (currentStop == null) {
            return
        }
        for (passenger in getPassengers()) {
            val passengerCart = passengerRegistry.cartFor(passenger)
            publishEnteringEventFor(passenger, passengerCart, currentStop, terminal)
            publishDockedEventFor(passenger, passengerCart, currentStop, terminal)
        }
    }

    private fun publishEnteringEventFor(
        passenger: HumanEntity,
        cart: Minecart?,
        stop: Stop?,
        terminal: Boolean,
    ) {
        if (passenger !is Player || stop == null) {
            return
        }
        val eventCart = cart ?: consist.getLeadCar()
        Bukkit.getPluginManager().callEvent(
            MetroTrainArrivalEvent(
                eventCart,
                passenger,
                line,
                stop,
                terminal,
                MetroTrainArrivalEvent.ArrivalType.ENTERING,
            ),
        )
    }

    private fun publishDockedEventFor(
        passenger: HumanEntity,
        cart: Minecart?,
        stop: Stop?,
        terminal: Boolean,
    ) {
        if (passenger !is Player || stop == null) {
            return
        }
        val eventCart = cart ?: consist.getLeadCar()
        Bukkit.getPluginManager().callEvent(
            MetroTrainArrivalEvent(
                eventCart,
                passenger,
                line,
                stop,
                terminal,
                MetroTrainArrivalEvent.ArrivalType.DOCKED,
            ),
        )
    }

    private fun currentStop(): Stop? {
        val currentStopId = getCurrentStopId()
        return if (currentStopId != null) service.stopManager.getStop(currentStopId) else null
    }

    private fun isTerminalStop(): Boolean =
        navigator.currentIndex >= navigator.stopIds.size - 1 && !service.isLoopLine

    private fun updatePassengerScoreboard(passenger: HumanEntity) {
        val player = passenger as? Player ?: return
        val scoreboardManager = service.plugin.scoreboardManager ?: return
        val currentStopId = getCurrentStopId()
        if (state == TrainState.WAITING && currentStopId != null) {
            scoreboardManager.updateEnteringStopScoreboard(player, line, currentStopId)
        } else if (state == TrainState.TERMINATING && currentStopId != null) {
            scoreboardManager.updateTerminalScoreboard(player, line, currentStopId)
        } else {
            scoreboardManager.updateTravelingScoreboard(player, line, getTargetStopId())
        }
    }

    fun tryRecordTravelTimeSample(currentTick: Long) {
        if (
            segmentStartTick < 0 ||
            navigator.targetIndex < 0 ||
            navigator.targetIndex >= navigator.stopIds.size
        ) {
            segmentStartTick = -1L
            segmentDepartPassengers.clear()
            return
        }
        val fromId = navigator.stopIds[max(0, navigator.currentIndex - 1)]
        val toId = navigator.stopIds[navigator.currentIndex]
        val durationSeconds = max(0.0, (currentTick - segmentStartTick) / 20.0)

        var hasCompletePassenger = false
        for (passengerId in segmentDepartPassengers) {
            if (passengerRegistry.contains(passengerId)) {
                hasCompletePassenger = true
                break
            }
        }

        val weight =
            if (hasCompletePassenger) {
                1.0
            } else if (service.plugin.isUseUnboardedSamples) {
                service.plugin.unboardedSampleWeight
            } else {
                0.0
            }

        if (weight > 0.0) {
            service.plugin.travelTimeEstimator.record(
                service.lineId,
                fromId,
                toId,
                durationSeconds,
                weight,
            )
        }

        segmentStartTick = -1L
        segmentDepartPassengers.clear()
    }

    private fun updateChunkLoading(currentTick: Long) {
        val plugin = service.plugin
        val interval = plugin.chunkLoadingUpdateIntervalTicks
        if (interval > 1 && currentTick - lastChunkUpdateTick < interval) {
            return
        }
        lastChunkUpdateTick = currentTick

        val shouldLoad =
            TrainRuntimeDecisions.shouldKeepChunksLoaded(
                service.plugin.isChunkLoadingEnabled,
                service.isGlobalMode,
                hasAnyPassengers(),
                plugin.isChunkLoadingOnlyWhenMoving,
                state,
            )

        if (!shouldLoad) {
            releaseAllForcedChunks()
            return
        }

        val cars = consist.getCars()
        if (cars.isEmpty()) {
            releaseAllForcedChunks()
            return
        }

        val radius = max(0, plugin.chunkLoadingRadius)
        val forwardRadius = max(0, plugin.forwardPreloadRadius)
        val desired = HashSet<Long>()
        for (cart in cars) {
            if (cart.isDead) {
                continue
            }
            val location = cart.location
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            addChunkSquare(desired, chunkX, chunkZ, radius)
        }

        val lead = consist.getLeadCar()
        if (lead != null && !lead.isDead && forwardRadius > 0) {
            val travelDirection = navigator.travelDirection
            val direction =
                if (travelDirection != null && travelDirection.lengthSquared() > 0) {
                    travelDirection.clone().setY(0)
                } else {
                    lead.velocity.clone().setY(0)
                }
            if (direction.lengthSquared() > 0.0001) {
                direction.normalize()
                val base = lead.location
                for (step in 1..forwardRadius) {
                    val ahead = base.clone().add(direction.clone().multiply(16 * step))
                    val aheadChunkX = ahead.blockX shr 4
                    val aheadChunkZ = ahead.blockZ shr 4
                    addChunkSquare(desired, aheadChunkX, aheadChunkZ, radius)
                }
            }
        }

        for (chunkKey in desired) {
            if (!forcedChunks.contains(chunkKey)) {
                forceChunk((chunkKey shr 32).toInt(), (chunkKey and 0xffffffffL).toInt(), true)
            }
        }
        for (chunkKey in HashSet(forcedChunks)) {
            if (!desired.contains(chunkKey)) {
                forceChunk((chunkKey shr 32).toInt(), (chunkKey and 0xffffffffL).toInt(), false)
            }
        }

        forcedChunks.clear()
        forcedChunks.addAll(desired)
    }

    private fun addChunkSquare(out: MutableSet<Long>, chunkX: Int, chunkZ: Int, radius: Int) {
        for (offsetX in -radius..radius) {
            for (offsetZ in -radius..radius) {
                out.add(packChunk(chunkX + offsetX, chunkZ + offsetZ))
            }
        }
    }

    private fun packChunk(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL)

    private fun forceChunk(chunkX: Int, chunkZ: Int, forced: Boolean) {
        val world = getLeadWorld() ?: return
        val center =
            Location(
                world,
                (chunkX shl 4) + 8.0,
                max(world.minHeight + 1, 64).toDouble(),
                (chunkZ shl 4) + 8.0,
            )
        SchedulerUtil.regionRun(
            service.plugin,
            center,
            Runnable {
                try {
                    world.setChunkForceLoaded(chunkX, chunkZ, forced)
                } catch (throwable: Throwable) {
                    service.plugin.logger.warning(
                        "Failed to set chunk force-loaded at $chunkX,$chunkZ: ${throwable.message}",
                    )
                }
            },
            0L,
            -1L,
        )
    }

    private fun getLeadWorld(): World? = consist.getLeadCar()?.world

    private fun releaseAllForcedChunks() {
        if (forcedChunks.isEmpty()) {
            return
        }
        val world = getLeadWorld()
        if (world == null) {
            forcedChunks.clear()
            return
        }
        for (chunkKey in HashSet(forcedChunks)) {
            forceChunk((chunkKey shr 32).toInt(), (chunkKey and 0xffffffffL).toInt(), false)
        }
        forcedChunks.clear()
    }

    private fun releaseRoutingReservation() {
        val occupiedSection = navigator.sectionKey
        if (occupiedSection != null) {
            service.blockSectionManager.leave(occupiedSection)
            navigator.sectionKey = null
        }
        navigator.travelDirection = null
    }

    private fun resetTransientRuntimeState() {
        readyToDepart = false
        segmentStartTick = -1L
        segmentDepartPassengers.clear()
        passengerRegistry.clear()
        idleVirtualTicks = 0L
        stalledTicks = 0
        virtualTrainId = null
    }

    fun prepareForBoarding(cart: Minecart?) {
        // No-op, conductor removed
    }

    fun forceWaitingState(stopIndex: Int, currentTick: Long) {
        navigator.currentIndex = max(0, min(stopIndex, navigator.stopIds.size - 1))
        navigator.targetIndex = -1
        state = TrainState.WAITING
        stateSinceTick = currentTick
        readyToDepart = false
        navigator.travelDirection = null
        consist.zeroVelocity()
    }

    @JvmOverloads
    fun forceArrivingState(
        targetIndex: Int,
        currentTick: Long,
        explicitDirection: Vector? = null,
    ) {
        navigator.targetIndex = max(0, min(targetIndex, navigator.stopIds.size - 1))
        navigator.currentIndex = navigator.targetIndex
        state = TrainState.MOVING
        stateSinceTick = currentTick
        readyToDepart = false

        if (explicitDirection != null && explicitDirection.lengthSquared() > 1e-6) {
            navigator.travelDirection = explicitDirection.clone().normalize()
        } else {
            val stop = service.stopManager.getStop(navigator.stopIds[navigator.targetIndex])
            if (stop != null) {
                navigator.travelDirection = LocationUtil.vectorFromYaw(stop.launchYaw)
            }
        }

        val travelDirection = navigator.travelDirection
        if (travelDirection == null || travelDirection.lengthSquared() < 1e-6) {
            navigator.travelDirection = Vector(0, 0, 1)
        }

        applyInitialBoost(navigator.travelDirection)
    }

    fun adjustStartIndex(startIndex: Int, currentTick: Long) {
        navigator.currentIndex = max(0, min(startIndex, navigator.stopIds.size - 1))
        navigator.targetIndex = -1
        state = TrainState.WAITING
        stateSinceTick = currentTick
        readyToDepart = true
    }

    fun getVirtualizationState(currentTick: Long): VirtualizationState {
        val stopIds = navigator.stopIds
        val waiting = TrainStateMath.isVirtualWaitingState(state)
        val progress =
            TrainStateMath.estimateVirtualProgress(
                state,
                navigator.currentIndex,
                navigator.targetIndex,
                stopIds,
                getSegmentElapsedSeconds(currentTick),
                TrainStateMath.SegmentSecondsLookup { fromStopId, toStopId ->
                    service.plugin.travelTimeEstimator.estimateSeconds(service.lineId, fromStopId, toStopId)
                },
            )

        return VirtualizationState(navigator.currentIndex, navigator.targetIndex, progress, waiting)
    }

    class VirtualizationState(
        @JvmField val currentIndex: Int,
        @JvmField val targetIndex: Int,
        @JvmField val progress: Double,
        @JvmField val isWaiting: Boolean,
    )
}
