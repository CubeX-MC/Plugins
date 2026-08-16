package org.cubexmc.metro.physics

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Array as ReflectArray
import java.util.Objects
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Minecart
import org.bukkit.plugin.Plugin

/** Reflection-based TrainCarts integration. Only used when the TrainCarts plugin is present. */
internal class TrainCartsBridge private constructor(plugin: Plugin) {

    private val trainCartsPlugin: Plugin = plugin
    private val minecartMemberStoreClass: Class<*>
    private val minecartMemberClass: Class<*>
    private val minecartGroupStoreClass: Class<*>
    private val minecartGroupClass: Class<*>
    private val trainPropertiesClass: Class<*>
    private val slowdownModeClass: Class<*>

    private val convertHandle: MethodHandle
    private val getFromEntityHandle: MethodHandle
    private val clearGroupHandle: MethodHandle

    private val createGroupHandle: MethodHandle
    private val setForwardForceHandle: MethodHandle
    private val stopGroupHandle: MethodHandle
    private val getPropertiesHandle: MethodHandle
    private val setSpeedLimitHandle: MethodHandle
    private val setSlowingDownHandle: MethodHandle
    private val setSlowdownModeHandle: MethodHandle

    private val slowdownDisabledEnum: Any?

    init {
        val lookup = MethodHandles.publicLookup()

        val loader = plugin.javaClass.classLoader
        val trainCartsClass = loader.loadClass("com.bergerkiller.bukkit.tc.TrainCarts")
        minecartMemberStoreClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartMemberStore")
        minecartMemberClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartMember")
        minecartGroupStoreClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartGroupStore")
        minecartGroupClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartGroup")
        trainPropertiesClass = loader.loadClass("com.bergerkiller.bukkit.tc.properties.TrainProperties")
        slowdownModeClass = loader.loadClass("com.bergerkiller.bukkit.tc.properties.standard.type.SlowdownMode")

        convertHandle = lookup.findStatic(
            minecartMemberStoreClass,
            "convert",
            MethodType.methodType(minecartMemberClass, trainCartsClass, Minecart::class.java),
        )

        getFromEntityHandle = lookup.findStatic(
            minecartMemberStoreClass,
            "getFromEntity",
            MethodType.methodType(minecartMemberClass, Entity::class.java),
        )

        clearGroupHandle = lookup.findVirtual(
            minecartMemberClass,
            "clearGroup",
            MethodType.methodType(Void.TYPE),
        )

        createGroupHandle = lookup.findStatic(
            minecartGroupStoreClass,
            "create",
            MethodType.methodType(
                minecartGroupClass,
                String::class.java,
                ReflectArray.newInstance(minecartMemberClass, 0).javaClass,
            ),
        )

        setForwardForceHandle = lookup.findVirtual(
            minecartGroupClass,
            "setForwardForce",
            MethodType.methodType(Void.TYPE, java.lang.Double.TYPE),
        )

        stopGroupHandle = lookup.findVirtual(
            minecartGroupClass,
            "stop",
            MethodType.methodType(Void.TYPE),
        )

        getPropertiesHandle = lookup.findVirtual(
            minecartGroupClass,
            "getProperties",
            MethodType.methodType(trainPropertiesClass),
        )

        setSpeedLimitHandle = lookup.findVirtual(
            trainPropertiesClass,
            "setSpeedLimit",
            MethodType.methodType(Void.TYPE, java.lang.Double.TYPE),
        )

        setSlowingDownHandle = lookup.findVirtual(
            trainPropertiesClass,
            "setSlowingDown",
            MethodType.methodType(Void.TYPE, java.lang.Boolean.TYPE),
        )

        setSlowdownModeHandle = lookup.findVirtual(
            trainPropertiesClass,
            "setSlowingDown",
            MethodType.methodType(Void.TYPE, slowdownModeClass, java.lang.Boolean.TYPE),
        )

        var disabledValue: Any? = null
        val slowdownConstants = slowdownModeClass.enumConstants
        if (slowdownConstants != null) {
            for (constant in slowdownConstants) {
                if (constant != null && constant.toString() == "DISABLED") {
                    disabledValue = constant
                    break
                }
            }
        }
        slowdownDisabledEnum = disabledValue
    }

    fun attach(carts: List<Minecart?>, speedLimit: Double): TrainHandle? {
        if (carts.isEmpty()) {
            return null
        }
        val members = ArrayList<Any>(carts.size)
        for (cart in carts) {
            if (cart == null || cart.isDead) {
                continue
            }
            var member = getMember(cart)
            if (member == null) {
                member = convert(cart)
            }
            if (member == null) {
                return null
            }
            try {
                clearGroupHandle.invoke(member)
            } catch (_: Throwable) {
                return null
            }
            members.add(member)
        }
        if (members.isEmpty()) {
            return null
        }
        return try {
            val memberArray = ReflectArray.newInstance(minecartMemberClass, members.size)
            for (index in members.indices) {
                ReflectArray.set(memberArray, index, members[index])
            }
            val group: Any? = createGroupHandle.invokeWithArguments(null as Any?, memberArray)
            if (group == null) {
                null
            } else {
                configureGroup(group, speedLimit)
                TrainHandle(group)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun setSpeed(group: Any, speed: Double) {
        try {
            setForwardForceHandle.invoke(group, speed)
        } catch (_: Throwable) {
            // Optional integration failures must not affect Railway operation.
        }
    }

    fun stop(group: Any) {
        try {
            stopGroupHandle.invoke(group)
        } catch (_: Throwable) {
            // Optional integration failures must not affect Railway operation.
        }
    }

    fun cleanup(group: Any?) {
        if (group == null) {
            return
        }
        try {
            stopGroupHandle.invoke(group)
        } catch (_: Throwable) {
            // Optional integration failures must not affect Railway operation.
        }
    }

    fun updateSpeedLimit(group: Any, speedLimit: Double) {
        try {
            val properties: Any? = getPropertiesHandle.invoke(group)
            if (properties != null) {
                setSpeedLimitHandle.invoke(properties, speedLimit)
            }
        } catch (_: Throwable) {
            // Optional integration failures must not affect Railway operation.
        }
    }

    private fun getMember(cart: Minecart): Any? {
        try {
            val member: Any? = getFromEntityHandle.invoke(cart)
            if (member != null) {
                return member
            }
        } catch (_: Throwable) {
            // Try conversion below.
        }
        return null
    }

    private fun convert(cart: Minecart): Any? =
        try {
            convertHandle.invoke(trainCartsPlugin, cart)
        } catch (_: Throwable) {
            null
        }

    private fun configureGroup(group: Any, speedLimit: Double) {
        try {
            val properties: Any? = getPropertiesHandle.invoke(group)
            if (properties != null) {
                setSpeedLimitHandle.invoke(properties, speedLimit)
                setSlowingDownHandle.invoke(properties, false)
                val disabled = slowdownDisabledEnum
                if (disabled != null) {
                    setSlowdownModeHandle.invoke(properties, disabled, false)
                }
            }
        } catch (_: Throwable) {
            // Optional integration failures must not affect Railway operation.
        }
    }

    internal class TrainHandle(group: Any) {
        private val group: Any = Objects.requireNonNull(group)

        fun setSpeed(bridge: TrainCartsBridge, speed: Double) {
            bridge.setSpeed(group, speed)
        }

        fun stop(bridge: TrainCartsBridge) {
            bridge.stop(group)
        }

        fun cleanup(bridge: TrainCartsBridge) {
            bridge.cleanup(group)
        }

        fun updateSpeedLimit(bridge: TrainCartsBridge, speedLimit: Double) {
            bridge.updateSpeedLimit(group, speedLimit)
        }
    }

    companion object {
        private val PLUGIN_NAMES = arrayOf("Train_Carts", "TrainCarts")

        @JvmStatic
        fun createIfAvailable(): TrainCartsBridge? {
            val plugin = findPlugin() ?: return null
            return try {
                TrainCartsBridge(plugin)
            } catch (exception: ReflectiveOperationException) {
                plugin.logger.warning("Metro: TrainCarts integration failed: ${exception.message}")
                null
            }
        }

        private fun findPlugin(): Plugin? {
            for (name in PLUGIN_NAMES) {
                val plugin = Bukkit.getPluginManager().getPlugin(name)
                if (plugin != null && plugin.isEnabled) {
                    return plugin
                }
            }
            return null
        }
    }
}
