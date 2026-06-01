package org.cubexmc.metro.physics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Minecart;
import org.bukkit.plugin.Plugin;

/**
 * Reflection based TrainCarts integration. Only used when the TrainCarts plugin is present.
 */
final class TrainCartsBridge {

    private static final String[] PLUGIN_NAMES = {"Train_Carts", "TrainCarts"};

    static TrainCartsBridge createIfAvailable() {
        Plugin plugin = findPlugin();
        if (plugin == null) {
            return null;
        }
        try {
            return new TrainCartsBridge(plugin);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Metro: TrainCarts integration failed: " + ex.getMessage());
            return null;
        }
    }

    private static Plugin findPlugin() {
        for (String name : PLUGIN_NAMES) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                return plugin;
            }
        }
        return null;
    }

    private final Plugin trainCartsPlugin;
    private final Class<?> minecartMemberStoreClass;
    private final Class<?> minecartMemberClass;
    private final Class<?> minecartGroupStoreClass;
    private final Class<?> minecartGroupClass;
    private final Class<?> trainPropertiesClass;
    private final Class<?> slowdownModeClass;

    private final MethodHandle convertHandle;
    private final MethodHandle getFromEntityHandle;
    private final MethodHandle clearGroupHandle;

    private final MethodHandle createGroupHandle;
    private final MethodHandle setForwardForceHandle;
    private final MethodHandle stopGroupHandle;
    private final MethodHandle getPropertiesHandle;
    private final MethodHandle setSpeedLimitHandle;
    private final MethodHandle setSlowingDownHandle;
    private final MethodHandle setSlowdownModeHandle;

    private final Object slowdownDisabledEnum;

    private TrainCartsBridge(Plugin plugin) throws ReflectiveOperationException {
        this.trainCartsPlugin = plugin;
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> trainCartsClass = loader.loadClass("com.bergerkiller.bukkit.tc.TrainCarts");
        this.minecartMemberStoreClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartMemberStore");
        this.minecartMemberClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartMember");
        this.minecartGroupStoreClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartGroupStore");
        this.minecartGroupClass = loader.loadClass("com.bergerkiller.bukkit.tc.controller.MinecartGroup");
        this.trainPropertiesClass = loader.loadClass("com.bergerkiller.bukkit.tc.properties.TrainProperties");
        this.slowdownModeClass = loader.loadClass("com.bergerkiller.bukkit.tc.properties.standard.type.SlowdownMode");

        this.convertHandle = lookup.findStatic(
                minecartMemberStoreClass,
                "convert",
                MethodType.methodType(minecartMemberClass, trainCartsClass, Minecart.class));

        this.getFromEntityHandle = lookup.findStatic(
                minecartMemberStoreClass,
                "getFromEntity",
                MethodType.methodType(minecartMemberClass, org.bukkit.entity.Entity.class));

        this.clearGroupHandle = lookup.findVirtual(
                minecartMemberClass,
                "clearGroup",
                MethodType.methodType(void.class));

        this.createGroupHandle = lookup.findStatic(
                minecartGroupStoreClass,
                "create",
                MethodType.methodType(minecartGroupClass, String.class, Array.newInstance(minecartMemberClass, 0).getClass()));

        this.setForwardForceHandle = lookup.findVirtual(
                minecartGroupClass,
                "setForwardForce",
                MethodType.methodType(void.class, double.class));

        this.stopGroupHandle = lookup.findVirtual(
                minecartGroupClass,
                "stop",
                MethodType.methodType(void.class));

        this.getPropertiesHandle = lookup.findVirtual(
                minecartGroupClass,
                "getProperties",
                MethodType.methodType(trainPropertiesClass));

        this.setSpeedLimitHandle = lookup.findVirtual(
                trainPropertiesClass,
                "setSpeedLimit",
                MethodType.methodType(void.class, double.class));

        this.setSlowingDownHandle = lookup.findVirtual(
                trainPropertiesClass,
                "setSlowingDown",
                MethodType.methodType(void.class, boolean.class));

        this.setSlowdownModeHandle = lookup.findVirtual(
                trainPropertiesClass,
                "setSlowingDown",
                MethodType.methodType(void.class, slowdownModeClass, boolean.class));

        Object disabledValue = null;
        Object[] slowdownConstants = slowdownModeClass.getEnumConstants();
        if (slowdownConstants != null) {
            for (Object constant : slowdownConstants) {
                if (constant != null && "DISABLED".equals(constant.toString())) {
                    disabledValue = constant;
                    break;
                }
            }
        }
        this.slowdownDisabledEnum = disabledValue;
    }

    TrainHandle attach(List<Minecart> carts, double speedLimit) {
        if (carts.isEmpty()) {
            return null;
        }
        List<Object> members = new ArrayList<>(carts.size());
        for (Minecart cart : carts) {
            if (cart == null || cart.isDead()) {
                continue;
            }
            Object member = getMember(cart);
            if (member == null) {
                member = convert(cart);
            }
            if (member == null) {
                return null;
            }
            try {
                clearGroupHandle.invoke(member);
            } catch (Throwable t) {
                return null;
            }
            members.add(member);
        }
        if (members.isEmpty()) {
            return null;
        }
        try {
            Object memberArray = Array.newInstance(minecartMemberClass, members.size());
            for (int i = 0; i < members.size(); i++) {
                Array.set(memberArray, i, members.get(i));
            }
            Object group = createGroupHandle.invokeWithArguments((Object) null, memberArray);
            if (group == null) {
                return null;
            }
            configureGroup(group, speedLimit);
            return new TrainHandle(group);
        } catch (Throwable t) {
            return null;
        }
    }

    void setSpeed(Object group, double speed) {
        try {
            setForwardForceHandle.invoke(group, speed);
        } catch (Throwable ignored) {
        }
    }

    void stop(Object group) {
        try {
            stopGroupHandle.invoke(group);
        } catch (Throwable ignored) {
        }
    }

    void cleanup(Object group) {
        if (group == null) {
            return;
        }
        try {
            stopGroupHandle.invoke(group);
        } catch (Throwable ignored) {
        }
    }

    void updateSpeedLimit(Object group, double speedLimit) {
        try {
            Object properties = getPropertiesHandle.invoke(group);
            if (properties != null) {
                setSpeedLimitHandle.invoke(properties, speedLimit);
            }
        } catch (Throwable ignored) {
        }
    }

    private Object getMember(Minecart cart) {
        try {
            Object member = getFromEntityHandle.invoke(cart);
            if (member != null) {
                return member;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object convert(Minecart cart) {
        try {
            return convertHandle.invoke(trainCartsPlugin, cart);
        } catch (Throwable t) {
            return null;
        }
    }

    private void configureGroup(Object group, double speedLimit) {
        try {
            Object properties = getPropertiesHandle.invoke(group);
            if (properties != null) {
                setSpeedLimitHandle.invoke(properties, speedLimit);
                setSlowingDownHandle.invoke(properties, false);
                if (slowdownDisabledEnum != null) {
                    setSlowdownModeHandle.invoke(properties, slowdownDisabledEnum, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static final class TrainHandle {
        private final Object group;
        TrainHandle(Object group) {
            this.group = Objects.requireNonNull(group);
        }

        void setSpeed(TrainCartsBridge bridge, double speed) {
            bridge.setSpeed(group, speed);
        }

        void stop(TrainCartsBridge bridge) {
            bridge.stop(group);
        }

        void cleanup(TrainCartsBridge bridge) {
            bridge.cleanup(group);
        }

        void updateSpeedLimit(TrainCartsBridge bridge, double speedLimit) {
            bridge.updateSpeedLimit(group, speedLimit);
        }
    }
}
