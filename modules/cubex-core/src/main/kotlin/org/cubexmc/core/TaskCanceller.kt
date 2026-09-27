package org.cubexmc.core

public fun interface TaskCanceller {
    @Throws(Exception::class)
    public fun cancel(taskHandle: Any)
}
