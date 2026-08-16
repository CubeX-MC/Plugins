package org.cubexmc.core

fun interface TaskCanceller {
    @Throws(Exception::class)
    fun cancel(taskHandle: Any)
}
