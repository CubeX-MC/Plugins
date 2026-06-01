package org.cubexmc.core;

public interface TerminableConsumer {

    <T extends AutoCloseable> T bind(T terminable);

    Terminable bind(Runnable closeAction);
}
