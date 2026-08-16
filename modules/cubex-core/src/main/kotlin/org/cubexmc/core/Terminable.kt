package org.cubexmc.core

import java.util.Objects

fun interface Terminable : AutoCloseable {
    @Throws(Exception::class)
    override fun close()

    companion object {
        @JvmStatic
        fun of(closeAction: Runnable): Terminable {
            Objects.requireNonNull(closeAction, "closeAction")
            return Terminable { closeAction.run() }
        }
    }
}
