package org.cubexmc.core

public interface TerminableConsumer {
    public fun <T : AutoCloseable> bind(terminable: T): T

    public fun bind(closeAction: Runnable): Terminable
}
