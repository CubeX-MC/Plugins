package org.cubexmc.metro.physics

import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.train.TrainInstance

/** Leashed mode: reactive movement + visual leash couplers. */
open class LeashedRailPhysics : ReactiveRailPhysics() {

    private var coupler: LeashCoupler? = null

    override fun init(train: TrainInstance) {
        super.init(train)
        val createdCoupler = LeashCoupler(train.service.plugin, train)
        coupler = createdCoupler
        createdCoupler.start()
    }

    override fun onDeparture(train: TrainInstance, fromStop: Stop?) {
        super.onDeparture(train, fromStop)
        if (coupler == null) {
            val createdCoupler = LeashCoupler(train.service.plugin, train)
            coupler = createdCoupler
            createdCoupler.start()
        }
    }

    override fun tick(train: TrainInstance, timeFraction: Double, currentTick: Long) {
        // Physics settings (setSlowWhenEmpty, setGravity, etc.) are handled by super.tick().
        super.tick(train, timeFraction, currentTick)
        coupler?.update()
    }

    override fun cleanup(train: TrainInstance) {
        coupler?.cleanup()
        coupler = null
        super.cleanup(train)
    }
}
