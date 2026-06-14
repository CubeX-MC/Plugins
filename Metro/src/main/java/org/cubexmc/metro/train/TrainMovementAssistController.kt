package org.cubexmc.metro.train

import kotlin.math.max

/**
 * Handles safe-mode movement assist for stalled minecarts.
 */
class TrainMovementAssistController(
    private val session: TrainSession,
    private val trainScheduler: TrainScheduler,
    private val physicsController: TrainPhysicsController,
    private val cancelHandler: Runnable,
    private val passengerExitHandler: Runnable,
) {
    private var movementAssistTaskId: Any? = null

    fun start() {
        stop()
        val minecart = session.minecart
        if (!session.plugin.configFacade.isSafeModeMovementAssist() || minecart == null) {
            return
        }
        val interval = max(1L, session.plugin.configFacade.getSafeModeStallRecoveryTicks())
        movementAssistTaskId = trainScheduler.entityRun(
            minecart,
            Runnable { recoverStalledMinecart() },
            interval,
            interval,
        )
    }

    fun stop() {
        trainScheduler.cancel(movementAssistTaskId)
        movementAssistTaskId = null
    }

    private fun recoverStalledMinecart() {
        if (!session.plugin.configFacade.isSafeModeMovementAssist()) {
            stop()
            return
        }
        val minecart = session.minecart
        if (minecart == null || minecart.isDead || !minecart.isValid) {
            cancelHandler.run()
            return
        }
        if (!physicsController.canRecoverStalledMinecart(session)) {
            return
        }
        if (!session.isPassengerStillRiding()) {
            passengerExitHandler.run()
            return
        }

        val minCruiseSpeed = max(0.01, session.plugin.configFacade.getSafeModeMinCruiseSpeed())
        if (!physicsController.isBelowCruiseSpeed(minecart, minCruiseSpeed)) {
            return
        }

        val targetSpeed = physicsController.resolveAssistSpeed(
            minecart,
            session.plugin.configFacade.getCartSpeed(),
            minCruiseSpeed,
        )
        val lastTravelDirection = session.lastTravelDirection ?: return
        minecart.velocity = physicsController.buildAssistVelocity(lastTravelDirection, targetSpeed)
    }
}
