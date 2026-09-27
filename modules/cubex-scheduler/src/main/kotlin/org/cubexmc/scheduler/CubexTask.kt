package org.cubexmc.scheduler

import org.cubexmc.core.Terminable

public interface CubexTask : Terminable {
    public fun cancel()

    public fun isCancelled(): Boolean

    public fun nativeHandle(): Any?

    override fun close(): Unit = cancel()
}
