package org.cubexmc.metro.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.util.SchedulerUtil;

/**
 * Manages visual entity models that replace minecart appearance.
 * <p>
 * When enabled, each minecart gets an invisible-but-visible-model living entity
 * that is synchronized to the minecart's position every tick. The minecart itself
 * is hidden from players. Players interact with (ride) the visual entity instead
 * of the minecart, enabling multi-passenger support.
 */
public class EntityModelController {

    public static final String MINECART_ENTITY_TYPE = "MINECART";
    private static final String DEFAULT_ENTITY_TYPE = "PIG";
    private static final double MIN_ENTITY_MODEL_SPACING = 0.8D;
    private static final double ENTITY_MODEL_SPACING_PADDING = 0.6D;

    private final Metro plugin;
    private final Map<UUID, LivingEntity> cartToModel = new ConcurrentHashMap<>();
    private final Map<UUID, Minecart> modelToCart = new ConcurrentHashMap<>();

    private double offsetY;
    private boolean multiPassenger;
    private String defaultEntityTypeRaw = "";
    private String lastWarnedInvalidType;
    private EntityDisplayConfig displayConfig = EntityDisplayConfig.empty();

    public EntityModelController(Metro plugin) {
        this.plugin = plugin;
    }

    /**
     * Reload settings from config. Should be called after config load.
     */
    public void reload() {
        this.offsetY = plugin.getConfig().getDouble("entity-model.offset-y", 0.0);
        this.multiPassenger = plugin.getConfig().getBoolean("entity-model.multi-passenger", false);
        this.defaultEntityTypeRaw = resolveDefaultEntityTypeRaw(plugin.getEntityTypeOverride(), plugin.getLeashMobTypeRaw());
        this.lastWarnedInvalidType = null;
        this.displayConfig = EntityDisplayConfig.load(new File(plugin.getDataFolder(), "entity.yml"), plugin.getLogger());
    }

    /**
     * Attach a visual entity model to a minecart using the global entity type.
     * The model entity is spawned at the minecart's location.
     */
    public void attachModel(Minecart cart) {
        attachModel(cart, null);
    }

    /**
     * Attach a visual entity model to a minecart with an optional per-line entity type override.
     * The model entity is spawned at the minecart's location.
     *
     * @param cart              the minecart to attach a model to
     * @param entityTypeOverride if non-null, use this entity type instead of the global config
     */
    public void attachModel(Minecart cart, String entityTypeOverride) {
        if (cart == null || cart.isDead()) return;

        String typeRaw = resolveEntityTypeRaw(entityTypeOverride, defaultEntityTypeRaw);
        EntityType entityType = parseEntityType(typeRaw);

        if (entityType == null) {
            warnInvalidEntityType(typeRaw);
            return;
        }
        if (cartToModel.containsKey(cart.getUniqueId())) return;

        EntityDisplayConfig.DisplaySettings settings = displayConfig.settingsFor(typeRaw);
        double modelOffsetY = settings.offsetY() != null ? settings.offsetY() : offsetY;
        Location spawnLoc = cart.getLocation().clone().add(0, modelOffsetY, 0);

        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> entityClass =
                (Class<? extends LivingEntity>) entityType.getEntityClass();

        LivingEntity model = spawnLoc.getWorld().spawn(spawnLoc, entityClass, ent -> {
            ent.setInvisible(false);
            ent.setSilent(true);
            ent.setInvulnerable(true);
            ent.setGravity(false);
            ent.setCollidable(false);
            ent.setCustomName(cart.getCustomName());
            ent.setCustomNameVisible(cart.isCustomNameVisible());
            if (ent instanceof Mob) {
                ((Mob) ent).setAI(false);
            }
        });
        displayConfig.applyProperties(model, settings);

        model.setRemoveWhenFarAway(false);

        cartToModel.put(cart.getUniqueId(), model);
        modelToCart.put(model.getUniqueId(), cart);

        makeCartInvisible(cart);
    }

    /**
     * Synchronize the visual entity's position to its minecart.
     * Called every tick for each cart in the consist.
     */
    public void syncPosition(Minecart cart) {
        if (cart == null || cart.isDead()) {
            cleanupModel(cart);
            return;
        }
        LivingEntity model = cartToModel.get(cart.getUniqueId());
        if (model == null || model.isDead()) {
            cartToModel.remove(cart.getUniqueId());
            if (model != null) modelToCart.remove(model.getUniqueId());
            return;
        }

        String typeRaw = model.getType() == null ? null : model.getType().name();
        EntityDisplayConfig.DisplaySettings settings = displayConfig.settingsFor(typeRaw);
        double modelOffsetY = settings.offsetY() != null ? settings.offsetY() : offsetY;
        Location target = cart.getLocation().clone().add(0, modelOffsetY, 0);
        target.setYaw(cart.getLocation().getYaw());
        target.setPitch(cart.getLocation().getPitch());

        SchedulerUtil.teleportEntity(model, target);
    }

    /**
     * Remove the visual entity model for a given minecart.
     */
    public void removeModel(Minecart cart) {
        cleanupModel(cart);
        makeCartVisible(cart);
    }

    /**
     * Get the visual entity associated with a minecart.
     */
    public LivingEntity getModelEntity(Minecart cart) {
        if (cart == null) return null;
        return cartToModel.get(cart.getUniqueId());
    }

    /**
     * Get the minecart associated with a visual entity (by UUID).
     */
    public Minecart getCartByModelEntity(UUID modelEntityId) {
        return modelToCart.get(modelEntityId);
    }

    /**
     * Check if a given entity UUID corresponds to a Railway visual model entity.
     */
    public boolean isModelEntity(UUID entityId) {
        return modelToCart.containsKey(entityId);
    }

    /**
     * Check if multi-passenger mode is enabled.
     */
    public boolean isMultiPassenger() {
        return multiPassenger;
    }

    public String getDefaultEntityTypeRaw() {
        return defaultEntityTypeRaw;
    }

    public double getRecommendedSpacing(String raw, double fallbackSpacing) {
        EntityDisplayConfig.DisplaySettings settings = displayConfig.settingsFor(raw);
        if (settings.spacing() != null && Double.isFinite(settings.spacing()) && settings.spacing() > 0) {
            return settings.spacing();
        }
        return recommendedSpacing(raw, fallbackSpacing);
    }

    /**
     * Hide a minecart from players by making it invisible.
     */
    public void makeCartInvisible(Minecart cart) {
        if (cart == null || cart.isDead()) return;
        cart.setSilent(true);
        invokeBooleanSetter(cart, "setVisibleByDefault", false);
        try {
            cart.getClass().getMethod("setInvisible", boolean.class).invoke(cart, true);
        } catch (Exception ignored) {
            // setInvisible may not be fully effective for minecarts on some versions
        }
    }

    /**
     * Restore minecart visibility (e.g., during cleanup).
     */
    public void makeCartVisible(Minecart cart) {
        if (cart == null || cart.isDead()) return;
        cart.setSilent(false);
        invokeBooleanSetter(cart, "setVisibleByDefault", true);
        invokeBooleanSetter(cart, "setInvisible", false);
    }

    /**
     * Remove all tracked model entities. Called on plugin disable.
     */
    public void shutdown() {
        for (Map.Entry<UUID, LivingEntity> entry : cartToModel.entrySet()) {
            LivingEntity model = entry.getValue();
            if (model != null && !model.isDead()) {
                model.remove();
            }
        }
        cartToModel.clear();
        modelToCart.clear();
    }

    private void cleanupModel(Minecart cart) {
        if (cart == null) return;
        LivingEntity model = cartToModel.remove(cart.getUniqueId());
        if (model != null) {
            modelToCart.remove(model.getUniqueId());
            if (!model.isDead()) {
                // Eject any passengers first
                for (org.bukkit.entity.Entity passenger : new java.util.ArrayList<>(model.getPassengers())) {
                    passenger.leaveVehicle();
                }
                model.remove();
            }
        }
    }

    private void warnInvalidEntityType(String typeRaw) {
        String display = typeRaw == null ? "" : typeRaw;
        if (display.equals(lastWarnedInvalidType)) {
            return;
        }
        lastWarnedInvalidType = display;
        plugin.getLogger().warning("EntityModel: invalid entity type '"
                + display + "', skipping model attach");
    }

    static String resolveEntityTypeRaw(String preferredRaw, String fallbackRaw) {
        if (hasText(preferredRaw)) {
            return preferredRaw.trim();
        }
        if (hasText(fallbackRaw)) {
            return fallbackRaw.trim();
        }
        return "";
    }

    static String resolveDefaultEntityTypeRaw(String overrideRaw, String leashMobTypeRaw) {
        if (hasText(overrideRaw)) {
            return overrideRaw.trim();
        }
        String inherited = resolveEntityTypeRaw(null, leashMobTypeRaw);
        if (parseEntityType(inherited) != null) {
            return inherited;
        }
        return DEFAULT_ENTITY_TYPE;
    }

    public static String normalizeLineEntityType(String raw) {
        String normalized = normalizeConfiguredEntityName(raw);
        if (normalized == null) {
            return null;
        }
        if (MINECART_ENTITY_TYPE.equals(normalized) || "CART".equals(normalized)) {
            return MINECART_ENTITY_TYPE;
        }
        EntityType entityType = parseEntityType(normalized);
        return entityType == null ? null : entityType.name();
    }

    public static boolean usesVisualEntity(String raw) {
        String normalized = normalizeLineEntityType(raw);
        return normalized != null && !MINECART_ENTITY_TYPE.equals(normalized);
    }

    public static double recommendedSpacing(String raw, double fallbackSpacing) {
        String normalized = normalizeLineEntityType(raw);
        double fallback = Double.isFinite(fallbackSpacing) && fallbackSpacing > 0
                ? fallbackSpacing
                : MIN_ENTITY_MODEL_SPACING;
        if (normalized == null || MINECART_ENTITY_TYPE.equals(normalized)) {
            return fallback;
        }
        return Math.max(fallback, Math.max(MIN_ENTITY_MODEL_SPACING,
                estimatedEntitySpacing(normalized) + ENTITY_MODEL_SPACING_PADDING));
    }

    public static List<String> suggestedEntityTypeNames() {
        List<String> suggestions = new ArrayList<>();
        suggestions.add(MINECART_ENTITY_TYPE.toLowerCase(Locale.ROOT));
        for (EntityType type : EntityType.values()) {
            if (type != null && type.isAlive() && type.getEntityClass() != null) {
                suggestions.add(type.name().toLowerCase(Locale.ROOT));
            }
        }
        Collections.sort(suggestions);
        return suggestions;
    }

    private static boolean hasText(String raw) {
        return raw != null && !raw.trim().isEmpty();
    }

    static EntityType parseEntityType(String raw) {
        String normalized = normalizeConfiguredEntityName(raw);
        if (normalized == null) return null;
        try {
            EntityType t = EntityType.valueOf(normalized);
            if (!t.isAlive()) return null;
            if (t.getEntityClass() == null) return null;
            return t;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String normalizeConfiguredEntityName(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        int namespacedSeparator = normalized.indexOf(':');
        if (namespacedSeparator >= 0 && namespacedSeparator + 1 < normalized.length()) {
            normalized = normalized.substring(namespacedSeparator + 1);
        }
        normalized = normalized.replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static double estimatedEntitySpacing(String normalizedEntityType) {
        return switch (normalizedEntityType) {
            case "RABBIT", "CHICKEN", "ALLAY", "BAT", "BEE", "ENDERMITE", "SILVERFISH",
                    "TROPICAL_FISH", "COD", "SALMON", "PUFFERFISH" -> 1.0D;
            case "CAT", "OCELOT", "FOX", "WOLF", "PIG", "SHEEP", "GOAT", "VILLAGER",
                    "WANDERING_TRADER" -> 1.4D;
            case "HORSE", "DONKEY", "MULE", "SKELETON_HORSE", "ZOMBIE_HORSE", "LLAMA",
                    "TRADER_LLAMA", "CAMEL" -> 2.0D;
            case "POLAR_BEAR", "PANDA", "HOGLIN", "ZOGLIN", "RAVAGER", "SNIFFER" -> 2.7D;
            case "IRON_GOLEM", "WARDEN" -> 2.2D;
            case "GHAST" -> 4.5D;
            case "ENDER_DRAGON" -> 16.0D;
            default -> 1.6D;
        };
    }

    private void invokeBooleanSetter(Minecart cart, String methodName, boolean value) {
        try {
            cart.getClass().getMethod(methodName, boolean.class).invoke(cart, value);
        } catch (Exception ignored) {
        }
    }
}
