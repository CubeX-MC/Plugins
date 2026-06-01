package org.cubexmc.core;

@FunctionalInterface
public interface TaskCanceller {

    void cancel(Object taskHandle) throws Exception;
}
