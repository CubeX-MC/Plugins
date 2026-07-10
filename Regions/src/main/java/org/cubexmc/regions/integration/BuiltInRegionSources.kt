package org.cubexmc.regions.integration

import org.bukkit.Location
import org.bukkit.World
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.RegionSourceRef
import java.lang.reflect.Method
import java.util.UUID

object BuiltInRegionSources {
    fun registerAll(registry: RegionSourceRegistry, plugin: RegionsPlugin) {
        registry.register(LandsRegionSource(plugin))
        registry.register(CuboidRegionSource())
    }
}

class LandsRegionSource(private val plugin: RegionsPlugin) : RegionSource {
    override val type: String = "lands"
    private var cachedIntegration: Any? = null
    private var warnedUnavailableApi = false

    override fun isAvailable(): Boolean =
        plugin.server.pluginManager.getPlugin("Lands") != null

    override fun resolve(ref: RegionSourceRef): ExternalRegion? {
        if (!isAvailable()) {
            return null
        }
        val land = ref.values["land"] ?: return null
        val area = ref.values["area"] ?: "default"
        return ExternalRegion("$land:$area", "$land/$area", type)
    }

    override fun contains(ref: RegionSourceRef, location: Location): Boolean {
        val integration = integration() ?: return false
        val area = areaAt(integration, location) ?: return false
        return matchesConfiguredArea(area, ref)
    }

    override fun getOwnedRegions(playerId: UUID): List<ExternalRegion> {
        val integration = integration() ?: return emptyList()
        val landsPlayer = invoke(integration, "getLandsPlayer", playerId)
            ?: invoke(integration, "getPlayer", playerId)
            ?: return emptyList()
        val lands = invoke(landsPlayer, "getLands") as? Iterable<*> ?: return emptyList()
        val result = ArrayList<ExternalRegion>()
        for (land in lands) {
            if (land == null) {
                continue
            }
            val landName = stringValue(land, "getName") ?: stringValue(land, "getId") ?: continue
            result.add(ExternalRegion(landName, landName, type))
        }
        return result
    }

    private fun integration(): Any? {
        if (!isAvailable()) {
            return null
        }
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
        if (integration == null) {
            warnApi("LandsIntegration API entrypoint was not found.")
            return null
        }
        cachedIntegration = integration
        return integration
    }

    private fun areaAt(integration: Any, location: Location): Any? {
        invoke(integration, "getArea", location)?.let { return it }
        invoke(integration, "getAreaByLoc", location)?.let { return it }
        val world = location.world ?: return null
        val landWorld = invoke(integration, "getWorld", world)
            ?: invoke(integration, "getWorld", world.name)
            ?: invoke(integration, "getLandWorld", world)
            ?: invoke(integration, "getLandWorld", world.name)
            ?: return null
        return invoke(landWorld, "getArea", location)
            ?: invoke(landWorld, "getAreaByLoc", location)
            ?: invoke(landWorld, "getArea", location.blockX, location.blockY, location.blockZ)
    }

    private fun matchesConfiguredArea(area: Any, ref: RegionSourceRef): Boolean {
        val wantedLand = ref.values["land"]
        val wantedArea = ref.values["area"] ?: "default"
        val land = invoke(area, "getLand")
        if (!wantedLand.isNullOrBlank() && land != null) {
            val landName = stringValue(land, "getName")
                ?: stringValue(land, "getId")
                ?: stringValue(land, "getUid")
            if (landName != null && !landName.equals(wantedLand, ignoreCase = true)) {
                return false
            }
        }
        val areaName = stringValue(area, "getName")
            ?: stringValue(area, "getId")
            ?: stringValue(area, "getUid")
        if (areaName != null && !wantedArea.equals("default", ignoreCase = true)) {
            return areaName.equals(wantedArea, ignoreCase = true)
        }
        return true
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
            if (!parametersMatch(method.parameterTypes, args)) {
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

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (index in types.indices) {
            val arg = args[index] ?: continue
            if (types[index].isPrimitive) {
                continue
            }
            if (!types[index].isAssignableFrom(arg.javaClass)) {
                if (types[index] == World::class.java && arg is World) {
                    continue
                }
                return false
            }
        }
        return true
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

class CuboidRegionSource : RegionSource {
    override val type: String = "cuboid"

    override fun isAvailable(): Boolean = true

    override fun resolve(ref: RegionSourceRef): ExternalRegion? =
        ExternalRegion(ref.values["id"] ?: ref.describe(), ref.values["name"] ?: ref.describe(), type)

    override fun contains(ref: RegionSourceRef, location: Location): Boolean {
        val world = ref.values["world"] ?: return false
        if (!world.equals(location.world?.name, ignoreCase = true)) {
            return false
        }
        val minX = ref.values["min-x"]?.toDoubleOrNull() ?: return false
        val minY = ref.values["min-y"]?.toDoubleOrNull() ?: return false
        val minZ = ref.values["min-z"]?.toDoubleOrNull() ?: return false
        val maxX = ref.values["max-x"]?.toDoubleOrNull() ?: return false
        val maxY = ref.values["max-y"]?.toDoubleOrNull() ?: return false
        val maxZ = ref.values["max-z"]?.toDoubleOrNull() ?: return false
        val xRange = minOf(minX, maxX)..maxOf(minX, maxX)
        val yRange = minOf(minY, maxY)..maxOf(minY, maxY)
        val zRange = minOf(minZ, maxZ)..maxOf(minZ, maxZ)
        return location.x in xRange && location.y in yRange && location.z in zRange
    }

    override fun getOwnedRegions(playerId: UUID): List<ExternalRegion> = emptyList()
}
