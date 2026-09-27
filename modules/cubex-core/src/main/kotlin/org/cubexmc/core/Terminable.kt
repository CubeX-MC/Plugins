package org.cubexmc.core

import java.util.Objects

public fun interface Terminable : AutoCloseable {
    @Throws(Exception::class)
    override fun close()

    public companion object {
        @JvmStatic
        public fun of(closeAction: Runnable): Terminable {
            Objects.requireNonNull(closeAction, "closeAction")
            return Terminable { closeAction.run() }
        }
    }
}
