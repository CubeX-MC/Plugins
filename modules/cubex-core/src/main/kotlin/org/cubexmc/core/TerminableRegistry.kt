package org.cubexmc.core

import java.util.ArrayDeque
import java.util.Deque
import java.util.Objects

/** A LIFO resource group for a plugin or one of its domain managers. */
class TerminableRegistry {
    private val terminables: Deque<AutoCloseable> = ArrayDeque()

    fun <T : AutoCloseable> bind(terminable: T): T {
        terminables.addLast(Objects.requireNonNull(terminable, "terminable"))
        return terminable
    }

    fun bind(closeAction: Runnable): Terminable = bind(Terminable.of(closeAction))

    fun closeAll(failureHandler: CloseFailureHandler) {
        while (terminables.isNotEmpty()) {
            val terminable = terminables.removeLast()
            try {
                terminable.close()
            } catch (exception: Exception) {
                failureHandler.handle(exception)
            }
        }
    }

    fun interface CloseFailureHandler {
        fun handle(exception: Exception)
    }
}
