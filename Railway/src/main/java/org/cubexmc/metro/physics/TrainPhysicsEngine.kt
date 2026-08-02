package org.cubexmc.metro.physics

import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.train.TrainInstance

interface TrainPhysicsEngine {

    fun init(train: TrainInstance)

    fun onDeparture(train: TrainInstance, fromStop: Stop?)

    fun tick(train: TrainInstance, timeFraction: Double, currentTick: Long)

    fun onArrival(train: TrainInstance, atStop: Stop?, currentTick: Long)

    fun cleanup(train: TrainInstance)
}
