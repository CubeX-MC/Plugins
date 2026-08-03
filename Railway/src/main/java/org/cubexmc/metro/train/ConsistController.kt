package org.cubexmc.metro.train

import org.cubexmc.metro.Metro

/**
 * Periodically enforces spacing and velocity alignment for a consist.
 */
class ConsistController(
    @Suppress("unused") private val plugin: Metro,
    @Suppress("unused") private val consist: TrainConsist,
) : Runnable {

    override fun run() {
        // Placeholder: keep-alive tick; spacing logic to be added later
    }
}
