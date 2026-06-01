package org.cubexmc.metro.physics;

import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.model.Stop;

public interface TrainPhysicsEngine {

    void init(TrainInstance train);

    void onDeparture(TrainInstance train, Stop fromStop);

    void tick(TrainInstance train, double timeFraction, long currentTick);

    void onArrival(TrainInstance train, Stop atStop, long currentTick);

    void cleanup(TrainInstance train);
}


