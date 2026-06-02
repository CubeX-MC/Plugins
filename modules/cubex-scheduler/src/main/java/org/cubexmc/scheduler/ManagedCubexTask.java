package org.cubexmc.scheduler;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.scheduler.BukkitTask;

final class ManagedCubexTask implements CubexTask {

    private final Runnable unregister;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Object nativeHandle;

    ManagedCubexTask(Runnable unregister) {
        this.unregister = unregister;
    }

    void attach(Object nativeHandle) {
        this.nativeHandle = nativeHandle;
        if (isCancelled()) {
            cancelNative(nativeHandle);
        }
    }

    void complete() {
        unregister.run();
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancelNative(nativeHandle);
            unregister.run();
        }
    }

    @Override
    public boolean isCancelled() {
        if (cancelled.get()) {
            return true;
        }
        Object handle = nativeHandle;
        if (handle instanceof WrappedTask) {
            return ((WrappedTask) handle).isCancelled();
        }
        if (handle instanceof BukkitTask) {
            return ((BukkitTask) handle).isCancelled();
        }
        if (handle instanceof CompletableFuture) {
            return ((CompletableFuture<?>) handle).isCancelled();
        }
        return false;
    }

    @Override
    public Object nativeHandle() {
        return nativeHandle;
    }

    static void cancelNative(Object handle) {
        if (handle == null) {
            return;
        }
        if (handle instanceof CubexTask) {
            ((CubexTask) handle).cancel();
            return;
        }
        if (handle instanceof WrappedTask) {
            ((WrappedTask) handle).cancel();
            return;
        }
        if (handle instanceof BukkitTask) {
            ((BukkitTask) handle).cancel();
            return;
        }
        if (handle instanceof CompletableFuture) {
            ((CompletableFuture<?>) handle).cancel(false);
            return;
        }
        try {
            handle.getClass().getMethod("cancel").invoke(handle);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
