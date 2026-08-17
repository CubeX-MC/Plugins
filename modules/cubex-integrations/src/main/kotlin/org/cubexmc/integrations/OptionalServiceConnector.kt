package org.cubexmc.integrations

import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.ServicesManager

/** Identifies an optional Bukkit service without linking the consumer to its API classes. */
data class OptionalServiceDescriptor(
    val pluginName: String,
    val apiClassName: String,
) {
    init {
        require(pluginName.isNotBlank()) { "pluginName must not be blank" }
        require(apiClassName.isNotBlank()) { "apiClassName must not be blank" }
    }
}

enum class ServiceUnavailableReason {
    PLUGIN_MISSING,
    PLUGIN_DISABLED,
    API_CLASS_MISSING,
    SERVICE_NOT_REGISTERED,
    SERVICE_TYPE_MISMATCH,
}

sealed interface OptionalServiceConnection {
    val descriptor: OptionalServiceDescriptor

    data class Connected(
        override val descriptor: OptionalServiceDescriptor,
        val provider: Plugin,
        val apiType: Class<*>,
        val service: Any,
    ) : OptionalServiceConnection

    data class Unavailable(
        override val descriptor: OptionalServiceDescriptor,
        val reason: ServiceUnavailableReason,
        val detail: String = "",
    ) : OptionalServiceConnection
}

/**
 * Resolves an optional service through its provider plugin's class loader.
 *
 * The consumer never compiles against or packages the provider API. This is important for Bukkit's
 * per-plugin class loaders: shading the same service interface into two plugin jars would create two
 * different `Class` identities and make [ServicesManager] lookup fail.
 *
 * Connections are deliberately not cached. A domain adapter can retry after a provider is enabled,
 * disabled or reloaded without making the consumer plugin depend on that provider's lifecycle.
 */
class OptionalServiceConnector(
    private val pluginManager: PluginManager,
    private val servicesManager: ServicesManager,
) {
    constructor(server: Server) : this(server.pluginManager, server.servicesManager)

    fun connect(descriptor: OptionalServiceDescriptor): OptionalServiceConnection {
        val provider = pluginManager.getPlugin(descriptor.pluginName)
            ?: return OptionalServiceConnection.Unavailable(
                descriptor,
                ServiceUnavailableReason.PLUGIN_MISSING,
            )
        if (!provider.isEnabled) {
            return OptionalServiceConnection.Unavailable(
                descriptor,
                ServiceUnavailableReason.PLUGIN_DISABLED,
            )
        }

        val apiType = try {
            Class.forName(descriptor.apiClassName, false, provider.javaClass.classLoader)
        } catch (ex: ClassNotFoundException) {
            return OptionalServiceConnection.Unavailable(
                descriptor,
                ServiceUnavailableReason.API_CLASS_MISSING,
                ex.message.orEmpty(),
            )
        } catch (ex: LinkageError) {
            return OptionalServiceConnection.Unavailable(
                descriptor,
                ServiceUnavailableReason.API_CLASS_MISSING,
                ex.message.orEmpty(),
            )
        }

        @Suppress("UNCHECKED_CAST")
        val service = servicesManager.load(apiType as Class<Any>)
            ?: return OptionalServiceConnection.Unavailable(
                descriptor,
                ServiceUnavailableReason.SERVICE_NOT_REGISTERED,
            )
        if (!apiType.isInstance(service)) {
            return OptionalServiceConnection.Unavailable(
                descriptor,
                ServiceUnavailableReason.SERVICE_TYPE_MISMATCH,
                service.javaClass.name,
            )
        }
        return OptionalServiceConnection.Connected(descriptor, provider, apiType, service)
    }
}
