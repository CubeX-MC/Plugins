package org.cubexmc.metro.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

/**
 * Reflection bridge that mirrors TrainCarts' direct NMS control over minecarts.
 * optimized with MethodHandles for high-performance access.
 */
public final class MinecartNmsUtil {

    private static final Object INIT_LOCK = new Object();

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static MethodHandle getHandleHandle;
    private static MethodHandle moveHandle;
    private static boolean moveHandleUsesRotation; // true if 5 args, false if 3 args

    private static MethodHandle rotationHandle;
    private static MethodHandle setYawHandle;
    private static MethodHandle setPitchHandle;

    private static MethodHandle setDeltaMovementHandle;
    private static MethodHandle vec3ConstructorHandle;
    private static boolean setDeltaMovementUsesVec3;

    private static final AtomicBoolean failureLogged = new AtomicBoolean(false);

    private MinecartNmsUtil() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> craftMinecart = Class.forName("org.bukkit.craftbukkit.entity.CraftMinecart");
                Method getHandleMethod = craftMinecart.getMethod("getHandle");
                getHandleHandle = lookup.unreflect(getHandleMethod);
                available = true;
            } catch (Throwable t) {
                available = false;
                logFailure("Failed to initialize minecart NMS bridge", t);
            } finally {
                initialized = true;
            }
        }
    }

    public static boolean snap(Minecart cart, Location location, Vector velocity, float yaw, float pitch) {
        init();
        if (!available || cart == null || location == null) {
            return false;
        }
        try {
            Object handle = getHandleHandle.invoke(cart);
            ensureNmsMethods(handle);

            if (moveHandle == null) {
                return false;
            }

            if (moveHandleUsesRotation) {
                moveHandle.invoke(handle, location.getX(), location.getY(), location.getZ(), yaw, pitch);
            } else {
                moveHandle.invoke(handle, location.getX(), location.getY(), location.getZ());
                applyRotation(handle, yaw, pitch);
            }

            if (velocity != null) {
                applyVelocity(handle, velocity);
            }
            return true;
        } catch (Throwable t) {
            available = false;
            logFailure("Failed to apply minecart NMS snap - falling back to Bukkit API", t);
            return false;
        }
    }

    private static void ensureNmsMethods(Object handle) throws Exception {
        if (moveHandle != null && setDeltaMovementHandle != null) {
            return;
        }

        synchronized (INIT_LOCK) {
            if (moveHandle == null) {
                discoverMoveMethods(handle);
            }
            if (setDeltaMovementHandle == null) {
                discoverVelocityMethods(handle);
            }
        }
    }

    private static void discoverMoveMethods(Object handle) throws Exception {
        Class<?> clazz = handle.getClass();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        // Try known method names first (5-parameter variants)
        for (String name : Arrays.asList("absMoveTo", "moveTo", "setLocation")) {
            Method method = findMethod(clazz, name, double.class, double.class, double.class, float.class, float.class);
            if (method != null) {
                moveHandle = lookup.unreflect(method);
                moveHandleUsesRotation = true;
                return;
            }
        }

        // Search for any public method with signature
        // (double,double,double,float,float)
        for (Method method : clazz.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 5 && isDouble(params[0]) && isDouble(params[1]) && isDouble(params[2])
                    && isFloat(params[3]) && isFloat(params[4])) {
                moveHandle = lookup.unreflect(method);
                moveHandleUsesRotation = true;
                return;
            }
        }

        // Try known three-parameter method names for position only
        for (String name : Arrays.asList("setPos", "setPosition", "b", "a")) {
            Method method = findMethod(clazz, name, double.class, double.class, double.class);
            if (method != null) {
                moveHandle = lookup.unreflect(method);
                moveHandleUsesRotation = false;
                discoverRotationMethods(clazz, lookup);
                return;
            }
        }

        // Search for any method with (double,double,double)
        for (Method method : clazz.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3 && isDouble(params[0]) && isDouble(params[1]) && isDouble(params[2])) {
                moveHandle = lookup.unreflect(method);
                moveHandleUsesRotation = false;
                discoverRotationMethods(clazz, lookup);
                return;
            }
        }

        moveHandle = null;
    }

    private static void discoverRotationMethods(Class<?> clazz, MethodHandles.Lookup lookup) {
        for (String name : Arrays.asList("setRotation", "setRot")) {
            Method method = findMethod(clazz, name, float.class, float.class);
            if (method != null) {
                try {
                    rotationHandle = lookup.unreflect(method);
                } catch (IllegalAccessException e) {
                    // ignore
                }
                return;
            }
        }

        // Fall back to single-axis rotation methods
        Method yawM = findMethod(clazz, "setYRot", float.class);
        Method pitchM = findMethod(clazz, "setXRot", float.class);

        try {
            if (yawM != null)
                setYawHandle = lookup.unreflect(yawM);
            if (pitchM != null)
                setPitchHandle = lookup.unreflect(pitchM);
        } catch (IllegalAccessException e) {
            // ignore
        }
    }

    private static void discoverVelocityMethods(Object handle) throws Exception {
        Class<?> clazz = handle.getClass();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        for (String name : Arrays.asList("setDeltaMovement", "setMot", "setVelocity")) {
            Method method = findMethod(clazz, name, double.class, double.class, double.class);
            if (method != null) {
                setDeltaMovementHandle = lookup.unreflect(method);
                setDeltaMovementUsesVec3 = false;
                return;
            }
        }

        for (Method method : clazz.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3 && isDouble(params[0]) && isDouble(params[1]) && isDouble(params[2])) {
                setDeltaMovementHandle = lookup.unreflect(method);
                setDeltaMovementUsesVec3 = false;
                return;
            }
        }

        // Try Vec3 signature
        Class<?> vec3 = null;
        try {
            vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            Constructor<?> ctor = vec3.getConstructor(double.class, double.class, double.class);
            vec3ConstructorHandle = lookup.unreflectConstructor(ctor);
        } catch (Throwable ignored) {
            vec3 = null;
        }

        if (vec3 != null) {
            for (String name : Arrays.asList("setDeltaMovement", "setMot")) {
                Method method = findMethod(clazz, name, vec3);
                if (method != null) {
                    setDeltaMovementHandle = lookup.unreflect(method);
                    setDeltaMovementUsesVec3 = true;
                    return;
                }
            }

            for (Method method : clazz.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && vec3.isAssignableFrom(params[0])) {
                    setDeltaMovementHandle = lookup.unreflect(method);
                    setDeltaMovementUsesVec3 = true;
                    return;
                }
            }
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... signature) {
        try {
            return clazz.getMethod(name, signature);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static boolean isDouble(Class<?> type) {
        return type == double.class || type == Double.class;
    }

    private static boolean isFloat(Class<?> type) {
        return type == float.class || type == Float.class;
    }

    private static void applyRotation(Object handle, float yaw, float pitch) throws Throwable {
        if (rotationHandle != null) {
            rotationHandle.invoke(handle, yaw, pitch);
            return;
        }
        if (setYawHandle != null) {
            setYawHandle.invoke(handle, yaw);
        }
        if (setPitchHandle != null) {
            setPitchHandle.invoke(handle, pitch);
        }
    }

    private static void applyVelocity(Object handle, Vector velocity) throws Throwable {
        if (setDeltaMovementHandle == null) {
            return;
        }
        if (setDeltaMovementUsesVec3) {
            Object vec = vec3ConstructorHandle != null
                    ? vec3ConstructorHandle.invoke(velocity.getX(), velocity.getY(), velocity.getZ())
                    : null;
            if (vec != null) {
                setDeltaMovementHandle.invoke(handle, vec);
            }
        } else {
            setDeltaMovementHandle.invoke(handle, velocity.getX(), velocity.getY(), velocity.getZ());
        }
    }

    private static void logFailure(String message, Throwable throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            Bukkit.getLogger().log(Level.FINE, "[Metro] " + message, throwable);
        }
    }
}
