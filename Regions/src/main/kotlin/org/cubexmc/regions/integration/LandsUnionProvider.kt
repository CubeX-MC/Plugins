package org.cubexmc.regions.integration

import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.UnionRef
import java.lang.reflect.Method
import java.util.UUID

class LandsUnionProvider(private val plugin: RegionsPlugin) : UnionProvider {
    override val type: String = "lands"
    private var cachedIntegration: Any? = null
    private var warnedUnavailableApi = false

    override fun isAvailable(): Boolean =
        plugin.server.pluginManager.getPlugin("Lands") != null && integration() != null

    override fun getUnion(playerId: UUID): UnionRef? {
        val holder = memberHolder(playerId) ?: return null
        val id = stringValue(holder, "getULID") ?: stringValue(holder, "getName") ?: return null
        val name = stringValue(holder, "getName") ?: id
        return UnionRef(id, name, type)
    }

    override fun areSameUnion(a: UUID, b: UUID): Boolean {
        val unionA = getUnion(a) ?: return false
        val unionB = getUnion(b) ?: return false
        return unionA.id == unionB.id
    }

    override fun areAllied(a: UUID, b: UUID): Boolean {
        val holderA = memberHolder(a) ?: return false
        val holderB = memberHolder(b) ?: return false
        return invoke(holderA, "isAlly", holderB) as? Boolean ?: false
    }

    override fun areEnemies(a: UUID, b: UUID): Boolean {
        val holderA = memberHolder(a) ?: return false
        val holderB = memberHolder(b) ?: return false
        return invoke(holderA, "isEnemy", holderB) as? Boolean ?: false
    }

    override fun placeholder(playerId: UUID, key: String): String? {
        val union = getUnion(playerId) ?: return null
        return when (key.lowercase()) {
            "id" -> union.id
            "name" -> union.name
            "type" -> union.providerType
            else -> null
        }
    }

    private fun memberHolder(playerId: UUID): Any? {
        val land = landOf(playerId) ?: return null
        return invoke(land, "getNation") ?: land
    }

    private fun landOf(playerId: UUID): Any? {
        val integration = integration() ?: return null
        val player = invoke(integration, "getLandPlayer", playerId)
            ?: invoke(integration, "getPlayer", playerId)
            ?: return null
        val lands = invoke(player, "getLands") as? Iterable<*>
            ?: invoke(player, "getTrustedLands") as? Iterable<*>
            ?: return null
        return lands.firstOrNull()
    }

    private fun integration(): Any? {
        cachedIntegration?.let { return it }
        val type = classOrNull("me.angeschossen.lands.api.LandsIntegration")
            ?: classOrNull("me.angeschossen.lands.api.integration.LandsIntegration")
            ?: run {
                warnApi("Lands is installed, but LandsIntegration API class was not found.")
                return null
            }
        val integration = invokeStatic(type, "of", plugin)
            ?: invokeStatic(type, "getInstance")
            ?: invokeStatic(type, "get")
            ?: run {
                warnApi("LandsIntegration API entrypoint was not found.")
                return null
            }
        cachedIntegration = integration
        return integration
    }

    private fun invoke(target: Any, methodName: String, vararg args: Any?): Any? =
        invokeMethod(target.javaClass.methods.asIterable(), target, methodName, *args)

    private fun invokeStatic(type: Class<*>, methodName: String, vararg args: Any?): Any? =
        invokeMethod(type.methods.asIterable(), null, methodName, *args)

    private fun invokeMethod(methods: Iterable<Method>, target: Any?, methodName: String, vararg args: Any?): Any? {
        for (method in methods) {
            if (!method.name.equals(methodName, ignoreCase = true) || method.parameterCount != args.size) {
                continue
            }
            return try {
                method.invoke(target, *args)
            } catch (ex: ReflectiveOperationException) {
                null
            } catch (ex: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    private fun stringValue(target: Any, methodName: String): String? =
        invoke(target, methodName)?.toString()

    private fun warnApi(message: String) {
        if (!warnedUnavailableApi) {
            plugin.logger.warning(message)
            warnedUnavailableApi = true
        }
    }

    private fun classOrNull(name: String): Class<*>? =
        try {
            Class.forName(name)
        } catch (ex: ClassNotFoundException) {
            null
        }
}
