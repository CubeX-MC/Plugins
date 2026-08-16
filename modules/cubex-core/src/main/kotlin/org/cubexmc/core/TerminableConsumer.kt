package org.cubexmc.core

interface TerminableConsumer {
    fun <T : AutoCloseable> bind(terminable: T): T

    fun bind(closeAction: Runnable): Terminable
}
