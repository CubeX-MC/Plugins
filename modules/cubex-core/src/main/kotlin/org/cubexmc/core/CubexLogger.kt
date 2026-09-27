package org.cubexmc.core

import java.util.Objects
import java.util.logging.Level
import java.util.logging.Logger

public class CubexLogger(delegate: Logger) {
    private val delegate: Logger = Objects.requireNonNull(delegate, "delegate")

    public fun info(message: String) {
        delegate.info(message)
    }

    public fun warn(message: String) {
        delegate.warning(message)
    }

    public fun warn(message: String, throwable: Throwable) {
        log(Level.WARNING, message, throwable)
    }

    public fun severe(message: String) {
        delegate.severe(message)
    }

    public fun severe(message: String, throwable: Throwable) {
        log(Level.SEVERE, message, throwable)
    }

    public fun debug(message: String) {
        delegate.fine(message)
    }

    public fun log(level: Level, message: String, throwable: Throwable) {
        delegate.log(level, message, throwable)
    }
}
