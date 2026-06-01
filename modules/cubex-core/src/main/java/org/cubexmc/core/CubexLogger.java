package org.cubexmc.core;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CubexLogger {

    private final Logger delegate;

    public CubexLogger(Logger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void info(String message) {
        delegate.info(message);
    }

    public void warn(String message) {
        delegate.warning(message);
    }

    public void warn(String message, Throwable throwable) {
        log(Level.WARNING, message, throwable);
    }

    public void severe(String message) {
        delegate.severe(message);
    }

    public void severe(String message, Throwable throwable) {
        log(Level.SEVERE, message, throwable);
    }

    public void debug(String message) {
        delegate.fine(message);
    }

    public void log(Level level, String message, Throwable throwable) {
        delegate.log(level, message, throwable);
    }
}
