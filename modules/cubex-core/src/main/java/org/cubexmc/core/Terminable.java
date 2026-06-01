package org.cubexmc.core;

import java.util.Objects;

@FunctionalInterface
public interface Terminable extends AutoCloseable {

    @Override
    void close() throws Exception;

    static Terminable of(Runnable closeAction) {
        Objects.requireNonNull(closeAction, "closeAction");
        return closeAction::run;
    }
}
