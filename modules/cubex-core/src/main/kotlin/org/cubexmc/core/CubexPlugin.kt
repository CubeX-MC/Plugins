package org.cubexmc.core

import java.io.File
import java.util.logging.Level
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

abstract class CubexPlugin : JavaPlugin(), TerminableConsumer {
    private val terminables = TerminableRegistry()
    private val sharedText = CubexText()
    private val sharedMessager = Messager()
    private var structuredLogger: CubexLogger? = null

    final override fun onEnable() {
        try {
            enablePlugin()
        } catch (exception: EnableAbortException) {
            logger.warning(exception.message)
            server.pluginManager.disablePlugin(this)
        } catch (throwable: Throwable) {
            onEnableFailure(throwable)
            server.pluginManager.disablePlugin(this)
        }
    }

    final override fun onDisable() {
        try {
            disablePlugin()
        } catch (throwable: Throwable) {
            logger.log(Level.WARNING, "Failed during plugin disable.", throwable)
        } finally {
            terminables.closeAll { exception ->
                logger.log(Level.WARNING, "Failed to close plugin resource.", exception)
            }
        }
    }

    @Throws(Exception::class)
    protected abstract fun enablePlugin()

    @Throws(Exception::class)
    protected open fun disablePlugin() = Unit

    protected open fun abortEnable(reason: String?): Unit {
        throw EnableAbortException(
            reason?.takeUnless(String::isBlank) ?: "Plugin enable aborted.",
        )
    }

    protected open fun onEnableFailure(throwable: Throwable) {
        logger.log(Level.SEVERE, "Failed to enable plugin.", throwable)
    }

    fun log(): CubexLogger {
        val existing = structuredLogger
        if (existing != null) return existing

        return CubexLogger(logger).also { structuredLogger = it }
    }

    fun messager(): Messager = sharedMessager

    fun text(): CubexText = sharedText

    protected fun registerListener(listener: Listener) {
        server.pluginManager.registerEvents(listener, this)
    }

    protected fun registerCommand(name: String, executor: CommandExecutor): Boolean {
        val command = getCommand(name)
        if (command == null) {
            logger.severe("Command '$name' is missing from plugin.yml; it will not respond.")
            return false
        }
        command.setExecutor(executor)
        if (executor is TabCompleter) {
            command.tabCompleter = executor
        }
        return true
    }

    protected fun saveResourcesIfMissing(vararg resourcePaths: String?) {
        for (resourcePath in resourcePaths) {
            if (resourcePath.isNullOrBlank()) continue
            if (resourcePath == "config.yml") {
                saveDefaultConfig()
                continue
            }
            val target = File(dataFolder, resourcePath)
            if (!target.exists()) {
                saveResource(resourcePath, false)
            }
        }
    }

    protected fun bindTask(taskHandle: Any?, canceller: TaskCanceller?): Terminable {
        if (taskHandle == null || canceller == null) {
            return Terminable.of(Runnable {})
        }
        val cancelTask = Runnable {
            try {
                canceller.cancel(taskHandle)
            } catch (exception: Exception) {
                throw TaskCancelRuntimeException(exception)
            }
        }
        return bind(cancelTask)
    }

    final override fun <T : AutoCloseable> bind(terminable: T): T = terminables.bind(terminable)

    final override fun bind(closeAction: Runnable): Terminable = terminables.bind(closeAction)

    private class EnableAbortException(message: String) : RuntimeException(message)

    private class TaskCancelRuntimeException(cause: Exception) : RuntimeException(cause)
}
