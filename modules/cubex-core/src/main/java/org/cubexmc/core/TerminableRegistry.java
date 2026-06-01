package org.cubexmc.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class TerminableRegistry {

    private final Deque<AutoCloseable> terminables = new ArrayDeque<>();

    <T extends AutoCloseable> T bind(T terminable) {
        terminables.addLast(Objects.requireNonNull(terminable, "terminable"));
        return terminable;
    }

    Terminable bind(Runnable closeAction) {
        return bind(Terminable.of(closeAction));
    }

    void closeAll(CloseFailureHandler failureHandler) {
        while (!terminables.isEmpty()) {
            AutoCloseable terminable = terminables.removeLast();
            try {
                terminable.close();
            } catch (Exception ex) {
                failureHandler.handle(ex);
            }
        }
    }

    @FunctionalInterface
    interface CloseFailureHandler {
        void handle(Exception exception);
    }
}
