package org.cubexmc.scheduler

import com.tcoded.folialib.wrapper.task.WrappedTask
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class ManagedCubexTask(private val unregister: Runnable) : CubexTask {
    private val cancelled = AtomicBoolean()

    @Volatile
    private var handle: Any? = null

    fun attach(nativeHandle: Any?) {
        handle = nativeHandle
        if (isCancelled()) cancelNative(nativeHandle)
    }

    fun complete() = unregister.run()

    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancelNative(handle)
            unregister.run()
        }
    }

    override fun isCancelled(): Boolean {
        if (cancelled.get()) return true
        return when (val nativeHandle = handle) {
            is WrappedTask -> nativeHandle.isCancelled
            is BukkitTask -> nativeHandle.isCancelled
            is CompletableFuture<*> -> nativeHandle.isCancelled
            else -> false
        }
    }

    override fun nativeHandle(): Any? = handle

    companion object {
        @JvmStatic
        fun cancelNative(handle: Any?) {
            when (handle) {
                null -> return
                is CubexTask -> handle.cancel()
                is WrappedTask -> handle.cancel()
                is BukkitTask -> handle.cancel()
                is CompletableFuture<*> -> handle.cancel(false)
                else -> try {
                    handle.javaClass.getMethod("cancel").invoke(handle)
                } catch (_: ReflectiveOperationException) {
                    // Unknown task handles are intentionally ignored by the compatibility adapter.
                }
            }
        }
    }
}
