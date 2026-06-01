package org.cubexmc.metro.physics;

import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.train.TrainInstance;

/**
 * Leashed mode: reactive movement + visual leash couplers.
 */
public class LeashedRailPhysics extends ReactiveRailPhysics {

    private LeashCoupler coupler;

    @Override
    public void init(TrainInstance train) {
        super.init(train);
        coupler = new LeashCoupler(train.getService().getPlugin(), train);
        coupler.start();
    }

    @Override
    public void onDeparture(TrainInstance train, Stop fromStop) {
        super.onDeparture(train, fromStop);
        if (coupler == null) {
            coupler = new LeashCoupler(train.getService().getPlugin(), train);
            coupler.start();
        }
    }

    @Override
    public void tick(TrainInstance train, double timeFraction, long currentTick) {
        // Physics settings (setSlowWhenEmpty, setGravity, etc.) are handled by
        // super.tick()
        super.tick(train, timeFraction, currentTick);
        if (coupler != null)
            coupler.update();
    }

    @Override
    public void cleanup(TrainInstance train) {
        if (coupler != null) {
            coupler.cleanup();
            coupler = null;
        }
        super.cleanup(train);
    }
}
