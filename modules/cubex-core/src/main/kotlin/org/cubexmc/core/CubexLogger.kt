package org.cubexmc.core

import java.util.Objects
import java.util.logging.Level
import java.util.logging.Logger

class CubexLogger(delegate: Logger) {
    private val delegate: Logger = Objects.requireNonNull(delegate, "delegate")

    fun info(message: String) {
        delegate.info(message)
    }

    fun warn(message: String) {
        delegate.warning(message)
    }

    fun warn(message: String, throwable: Throwable) {
        log(Level.WARNING, message, throwable)
    }

    fun severe(message: String) {
        delegate.severe(message)
    }

    fun severe(message: String, throwable: Throwable) {
        log(Level.SEVERE, message, throwable)
    }

    fun debug(message: String) {
        delegate.fine(message)
    }

    fun log(level: Level, message: String, throwable: Throwable) {
        delegate.log(level, message, throwable)
    }
}
