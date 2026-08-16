package org.cubexmc.scheduler

import org.cubexmc.core.Terminable

interface CubexTask : Terminable {
    fun cancel()

    fun isCancelled(): Boolean

    fun nativeHandle(): Any?

    override fun close() = cancel()
}
