package org.cubexmc.core;

import java.io.File;
import java.util.logging.Level;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class CubexPlugin extends JavaPlugin implements TerminableConsumer {

    private final TerminableRegistry terminables = new TerminableRegistry();
    private final CubexText text = new CubexText();
    private final Messager messager = new Messager();
    private CubexLogger logger;

    @Override
    public final void onEnable() {
        try {
            enablePlugin();
        } catch (EnableAbortException ex) {
            getLogger().warning(ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (Throwable throwable) {
            onEnableFailure(throwable);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public final void onDisable() {
        try {
            disablePlugin();
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Failed during plugin disable.", throwable);
        } finally {
            terminables.closeAll(exception ->
                    getLogger().log(Level.WARNING, "Failed to close plugin resource.", exception));
        }
    }

    protected abstract void enablePlugin() throws Exception;

    protected void disablePlugin() throws Exception {
    }

    protected void abortEnable(String reason) {
        throw new EnableAbortException(reason == null || reason.isBlank() ? "Plugin enable aborted." : reason);
    }

    protected void onEnableFailure(Throwable throwable) {
        getLogger().log(Level.SEVERE, "Failed to enable plugin.", throwable);
    }

    protected final CubexLogger log() {
        if (logger == null) {
            logger = new CubexLogger(getLogger());
        }
        return logger;
    }

    protected final Messager messager() {
        return messager;
    }

    protected final CubexText text() {
        return text;
    }

    protected final void registerListener(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    protected final void saveResourcesIfMissing(String... resourcePaths) {
        if (resourcePaths == null) {
            return;
        }
        for (String resourcePath : resourcePaths) {
            if (resourcePath == null || resourcePath.isBlank()) {
                continue;
            }
            if ("config.yml".equals(resourcePath)) {
                saveDefaultConfig();
                continue;
            }
            File target = new File(getDataFolder(), resourcePath);
            if (!target.exists()) {
                saveResource(resourcePath, false);
            }
        }
    }

    protected final Terminable bindTask(Object taskHandle, TaskCanceller canceller) {
        if (taskHandle == null || canceller == null) {
            return Terminable.of(() -> {
            });
        }
        Runnable cancelTask = () -> {
            try {
                canceller.cancel(taskHandle);
            } catch (Exception ex) {
                throw new TaskCancelRuntimeException(ex);
            }
        };
        return bind(cancelTask);
    }

    @Override
    public final <T extends AutoCloseable> T bind(T terminable) {
        return terminables.bind(terminable);
    }

    @Override
    public final Terminable bind(Runnable closeAction) {
        return terminables.bind(closeAction);
    }

    private static final class EnableAbortException extends RuntimeException {
        private EnableAbortException(String message) {
            super(message);
        }
    }

    private static final class TaskCancelRuntimeException extends RuntimeException {
        private TaskCancelRuntimeException(Exception cause) {
            super(cause);
        }
    }
}
