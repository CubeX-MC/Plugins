package org.cubexmc.metro.train;

import org.cubexmc.metro.Metro;

/**
 * Periodically enforces spacing and velocity alignment for a consist.
 */
public class ConsistController implements Runnable {

    private final Metro plugin;
    private final TrainConsist consist;

    public ConsistController(Metro plugin, TrainConsist consist) {
        this.plugin = plugin;
        this.consist = consist;
    }

    @Override
    public void run() {
        // Placeholder: keep-alive tick; spacing logic to be added later
    }
}
