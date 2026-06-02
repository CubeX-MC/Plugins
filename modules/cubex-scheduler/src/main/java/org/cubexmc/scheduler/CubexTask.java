package org.cubexmc.scheduler;

import org.cubexmc.core.Terminable;

public interface CubexTask extends Terminable {

    void cancel();

    boolean isCancelled();

    Object nativeHandle();

    @Override
    default void close() {
        cancel();
    }
}
