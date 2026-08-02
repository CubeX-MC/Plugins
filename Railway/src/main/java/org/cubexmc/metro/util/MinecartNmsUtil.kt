package org.cubexmc.metro.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Reflection bridge that mirrors TrainCarts' direct NMS control over minecarts.
 * optimized with MethodHandles for high-performance access.
 */
object MinecartNmsUtil {

    private val INIT_LOCK = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var available = false

    private var getHandleHandle: MethodHandle? = null
    private var moveHandle: MethodHandle? = null
    private var moveHandleUsesRotation = false // true if 5 args, false if 3 args

    private var rotationHandle: MethodHandle? = null
    private var setYawHandle: MethodHandle? = null
    private var setPitchHandle: MethodHandle? = null

    private var setDeltaMovementHandle: MethodHandle? = null
    private var vec3ConstructorHandle: MethodHandle? = null
    private var setDeltaMovementUsesVec3 = false

    private val failureLogged = AtomicBoolean(false)

    private fun init() {
        if (initialized) {
            return
        }
        synchronized(INIT_LOCK) {
            if (initialized) {
                return
            }
            try {
                val lookup = MethodHandles.publicLookup()
                val craftMinecart = Class.forName("org.bukkit.craftbukkit.entity.CraftMinecart")
                val getHandleMethod = craftMinecart.getMethod("getHandle")
                getHandleHandle = lookup.unreflect(getHandleMethod)
                available = true
            } catch (t: Throwable) {
                available = false
                logFailure("Failed to initialize minecart NMS bridge", t)
            } finally {
                initialized = true
            }
        }
    }

    @JvmStatic
    fun snap(cart: Minecart?, location: Location?, velocity: Vector?, yaw: Float, pitch: Float): Boolean {
        init()
        val handleAccessor = getHandleHandle
        if (!available || cart == null || location == null || handleAccessor == null) {
            return false
        }
        return try {
            val handle: Any = handleAccessor.invoke(cart)
            ensureNmsMethods(handle)

            val move = moveHandle ?: return false

            if (moveHandleUsesRotation) {
                move.invoke(handle, location.x, location.y, location.z, yaw, pitch)
            } else {
                move.invoke(handle, location.x, location.y, location.z)
                applyRotation(handle, yaw, pitch)
            }

            if (velocity != null) {
                applyVelocity(handle, velocity)
            }
            true
        } catch (t: Throwable) {
            available = false
            logFailure("Failed to apply minecart NMS snap - falling back to Bukkit API", t)
            false
        }
    }

    private fun ensureNmsMethods(handle: Any) {
        if (moveHandle != null && setDeltaMovementHandle != null) {
            return
        }

        synchronized(INIT_LOCK) {
            if (moveHandle == null) {
                discoverMoveMethods(handle)
            }
            if (setDeltaMovementHandle == null) {
                discoverVelocityMethods(handle)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun discoverMoveMethods(handle: Any) {
        val clazz: Class<*> = handle.javaClass
        val lookup = MethodHandles.publicLookup()

        // Try known method names first (5-parameter variants)
        for (name in listOf("absMoveTo", "moveTo", "setLocation")) {
            val method = findMethod(
                clazz,
                name,
                DOUBLE_TYPE,
                DOUBLE_TYPE,
                DOUBLE_TYPE,
                FLOAT_TYPE,
                FLOAT_TYPE,
            )
            if (method != null) {
                moveHandle = lookup.unreflect(method)
                moveHandleUsesRotation = true
                return
            }
        }

        // Search for any public method with signature
        // (double,double,double,float,float)
        for (method in clazz.methods) {
            val params = method.parameterTypes
            if (params.size == 5 && isDouble(params[0]) && isDouble(params[1]) && isDouble(params[2]) &&
                isFloat(params[3]) && isFloat(params[4])
            ) {
                moveHandle = lookup.unreflect(method)
                moveHandleUsesRotation = true
                return
            }
        }

        // Try known three-parameter method names for position only
        for (name in listOf("setPos", "setPosition", "b", "a")) {
            val method = findMethod(clazz, name, DOUBLE_TYPE, DOUBLE_TYPE, DOUBLE_TYPE)
            if (method != null) {
                moveHandle = lookup.unreflect(method)
                moveHandleUsesRotation = false
                discoverRotationMethods(clazz, lookup)
                return
            }
        }

        // Search for any method with (double,double,double)
        for (method in clazz.methods) {
            val params = method.parameterTypes
            if (params.size == 3 && isDouble(params[0]) && isDouble(params[1]) && isDouble(params[2])) {
                moveHandle = lookup.unreflect(method)
                moveHandleUsesRotation = false
                discoverRotationMethods(clazz, lookup)
                return
            }
        }

        moveHandle = null
    }

    private fun discoverRotationMethods(clazz: Class<*>, lookup: MethodHandles.Lookup) {
        for (name in listOf("setRotation", "setRot")) {
            val method = findMethod(clazz, name, FLOAT_TYPE, FLOAT_TYPE)
            if (method != null) {
                try {
                    rotationHandle = lookup.unreflect(method)
                } catch (_: IllegalAccessException) {
                    // ignore
                }
                return
            }
        }

        // Fall back to single-axis rotation methods
        val yawM = findMethod(clazz, "setYRot", FLOAT_TYPE)
        val pitchM = findMethod(clazz, "setXRot", FLOAT_TYPE)

        try {
            if (yawM != null) {
                setYawHandle = lookup.unreflect(yawM)
            }
            if (pitchM != null) {
                setPitchHandle = lookup.unreflect(pitchM)
            }
        } catch (_: IllegalAccessException) {
            // ignore
        }
    }

    @Suppress("ReturnCount")
    private fun discoverVelocityMethods(handle: Any) {
        val clazz: Class<*> = handle.javaClass
        val lookup = MethodHandles.publicLookup()

        for (name in listOf("setDeltaMovement", "setMot", "setVelocity")) {
            val method = findMethod(clazz, name, DOUBLE_TYPE, DOUBLE_TYPE, DOUBLE_TYPE)
            if (method != null) {
                setDeltaMovementHandle = lookup.unreflect(method)
                setDeltaMovementUsesVec3 = false
                return
            }
        }

        for (method in clazz.methods) {
            val params = method.parameterTypes
            if (params.size == 3 && isDouble(params[0]) && isDouble(params[1]) && isDouble(params[2])) {
                setDeltaMovementHandle = lookup.unreflect(method)
                setDeltaMovementUsesVec3 = false
                return
            }
        }

        // Try Vec3 signature
        val vec3 = resolveVec3(lookup) ?: return

        for (name in listOf("setDeltaMovement", "setMot")) {
            val method = findMethod(clazz, name, vec3)
            if (method != null) {
                setDeltaMovementHandle = lookup.unreflect(method)
                setDeltaMovementUsesVec3 = true
                return
            }
        }

        for (method in clazz.methods) {
            val params = method.parameterTypes
            if (params.size == 1 && vec3.isAssignableFrom(params[0])) {
                setDeltaMovementHandle = lookup.unreflect(method)
                setDeltaMovementUsesVec3 = true
                return
            }
        }
    }

    private fun resolveVec3(lookup: MethodHandles.Lookup): Class<*>? =
        try {
            val vec3 = Class.forName("net.minecraft.world.phys.Vec3")
            val ctor = vec3.getConstructor(DOUBLE_TYPE, DOUBLE_TYPE, DOUBLE_TYPE)
            vec3ConstructorHandle = lookup.unreflectConstructor(ctor)
            vec3
        } catch (_: Throwable) {
            null
        }

    private fun findMethod(clazz: Class<*>, name: String, vararg signature: Class<*>?): Method? =
        try {
            clazz.getMethod(name, *signature)
        } catch (_: NoSuchMethodException) {
            null
        }

    private fun isDouble(type: Class<*>): Boolean = type == DOUBLE_TYPE || type == java.lang.Double::class.java

    private fun isFloat(type: Class<*>): Boolean = type == FLOAT_TYPE || type == java.lang.Float::class.java

    private fun applyRotation(handle: Any, yaw: Float, pitch: Float) {
        val rotation = rotationHandle
        if (rotation != null) {
            rotation.invoke(handle, yaw, pitch)
            return
        }
        setYawHandle?.invoke(handle, yaw)
        setPitchHandle?.invoke(handle, pitch)
    }

    private fun applyVelocity(handle: Any, velocity: Vector) {
        val setDelta = setDeltaMovementHandle ?: return
        if (setDeltaMovementUsesVec3) {
            val vec: Any? = vec3ConstructorHandle?.invoke(velocity.x, velocity.y, velocity.z)
            if (vec != null) {
                setDelta.invoke(handle, vec)
            }
        } else {
            setDelta.invoke(handle, velocity.x, velocity.y, velocity.z)
        }
    }

    private fun logFailure(message: String, throwable: Throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            Bukkit.getLogger().log(Level.FINE, "[Metro] $message", throwable)
        }
    }

    private val DOUBLE_TYPE: Class<*> = java.lang.Double.TYPE
    private val FLOAT_TYPE: Class<*> = java.lang.Float.TYPE
}
