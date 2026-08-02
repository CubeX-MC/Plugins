package org.cubexmc.metro.model

import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Mob
import org.cubexmc.metro.Metro
import org.cubexmc.metro.util.SchedulerUtil

/**
 * Manages visual entity models that replace minecart appearance.
 *
 * When enabled, each minecart gets an invisible-but-visible-model living entity
 * that is synchronized to the minecart's position every tick. The minecart itself
 * is hidden from players. Players interact with (ride) the visual entity instead
 * of the minecart, enabling multi-passenger support.
 */
class EntityModelController(
    private val plugin: Metro,
) {
    private val cartToModel: MutableMap<UUID, LivingEntity> = ConcurrentHashMap()
    private val modelToCart: MutableMap<UUID, Minecart> = ConcurrentHashMap()

    private var offsetY = 0.0
    private var multiPassenger = false
    private var defaultEntityTypeRaw = ""
    private var lastWarnedInvalidType: String? = null
    private var displayConfig = EntityDisplayConfig.empty()

    /**
     * Reload settings from config. Should be called after config load.
     */
    fun reload() {
        offsetY = plugin.config.getDouble("entity-model.offset-y", 0.0)
        multiPassenger = plugin.config.getBoolean("entity-model.multi-passenger", false)
        defaultEntityTypeRaw = resolveDefaultEntityTypeRaw(plugin.entityTypeOverride, plugin.leashMobTypeRaw)
        lastWarnedInvalidType = null
        displayConfig = EntityDisplayConfig.load(File(plugin.dataFolder, "entity.yml"), plugin.logger)
    }

    /**
     * Attach a visual entity model to a minecart using the global entity type.
     * The model entity is spawned at the minecart's location.
     */
    fun attachModel(cart: Minecart?) {
        attachModel(cart, null)
    }

    /**
     * Attach a visual entity model to a minecart with an optional per-line entity type override.
     * The model entity is spawned at the minecart's location.
     *
     * @param cart the minecart to attach a model to
     * @param entityTypeOverride if non-null, use this entity type instead of the global config
     */
    fun attachModel(cart: Minecart?, entityTypeOverride: String?) {
        if (cart == null || cart.isDead) {
            return
        }

        val typeRaw = resolveEntityTypeRaw(entityTypeOverride, defaultEntityTypeRaw)
        val entityType = parseEntityType(typeRaw)
        if (entityType == null) {
            warnInvalidEntityType(typeRaw)
            return
        }
        if (cartToModel.containsKey(cart.uniqueId)) {
            return
        }

        val settings = displayConfig.settingsFor(typeRaw)
        val modelOffsetY = settings.offsetY ?: offsetY
        val spawnLoc = cart.location.clone().add(0.0, modelOffsetY, 0.0)

        @Suppress("UNCHECKED_CAST")
        val entityClass = entityType.entityClass as Class<out LivingEntity>
        val world = spawnLoc.world ?: throw NullPointerException("world")
        val model = world.spawn(spawnLoc, entityClass) { entity ->
            entity.isInvisible = false
            entity.isSilent = true
            entity.isInvulnerable = true
            entity.setGravity(false)
            entity.isCollidable = false
            entity.customName = cart.customName
            entity.isCustomNameVisible = cart.isCustomNameVisible
            if (entity is Mob) {
                entity.setAI(false)
            }
        }
        displayConfig.applyProperties(model, settings)

        model.removeWhenFarAway = false

        cartToModel[cart.uniqueId] = model
        modelToCart[model.uniqueId] = cart

        makeCartInvisible(cart)
    }

    /**
     * Synchronize the visual entity's position to its minecart.
     * Called every tick for each cart in the consist.
     */
    fun syncPosition(cart: Minecart?) {
        if (cart == null || cart.isDead) {
            cleanupModel(cart)
            return
        }
        val model = cartToModel[cart.uniqueId]
        if (model == null || model.isDead) {
            cartToModel.remove(cart.uniqueId)
            if (model != null) {
                modelToCart.remove(model.uniqueId)
            }
            return
        }

        val modelType: EntityType? = model.type
        val typeRaw = modelType?.name
        val settings = displayConfig.settingsFor(typeRaw)
        val modelOffsetY = settings.offsetY ?: offsetY
        val target = cart.location.clone().add(0.0, modelOffsetY, 0.0)
        target.yaw = cart.location.yaw
        target.pitch = cart.location.pitch

        SchedulerUtil.teleportEntity(model, target)
    }

    /**
     * Remove the visual entity model for a given minecart.
     */
    fun removeModel(cart: Minecart?) {
        cleanupModel(cart)
        makeCartVisible(cart)
    }

    /**
     * Get the visual entity associated with a minecart.
     */
    fun getModelEntity(cart: Minecart?): LivingEntity? =
        cart?.let { cartToModel[it.uniqueId] }

    /**
     * Get the minecart associated with a visual entity (by UUID).
     */
    fun getCartByModelEntity(modelEntityId: UUID): Minecart? = modelToCart[modelEntityId]

    /**
     * Check if a given entity UUID corresponds to a Railway visual model entity.
     */
    fun isModelEntity(entityId: UUID): Boolean = modelToCart.containsKey(entityId)

    /**
     * Check if multi-passenger mode is enabled.
     */
    fun isMultiPassenger(): Boolean = multiPassenger

    fun getDefaultEntityTypeRaw(): String = defaultEntityTypeRaw

    fun getRecommendedSpacing(raw: String?, fallbackSpacing: Double): Double {
        val settings = displayConfig.settingsFor(raw)
        val spacing = settings.spacing
        if (spacing != null && spacing.isFinite() && spacing > 0) {
            return spacing
        }
        return recommendedSpacing(raw, fallbackSpacing)
    }

    /**
     * Hide a minecart from players by making it invisible.
     */
    fun makeCartInvisible(cart: Minecart?) {
        if (cart == null || cart.isDead) {
            return
        }
        cart.isSilent = true
        invokeBooleanSetter(cart, "setVisibleByDefault", false)
        try {
            cart.javaClass.getMethod("setInvisible", java.lang.Boolean.TYPE).invoke(cart, true)
        } catch (_: Exception) {
            // setInvisible may not be fully effective for minecarts on some versions
        }
    }

    /**
     * Restore minecart visibility (e.g., during cleanup).
     */
    fun makeCartVisible(cart: Minecart?) {
        if (cart == null || cart.isDead) {
            return
        }
        cart.isSilent = false
        invokeBooleanSetter(cart, "setVisibleByDefault", true)
        invokeBooleanSetter(cart, "setInvisible", false)
    }

    /**
     * Remove all tracked model entities. Called on plugin disable.
     */
    fun shutdown() {
        for (model in cartToModel.values) {
            if (!model.isDead) {
                model.remove()
            }
        }
        cartToModel.clear()
        modelToCart.clear()
    }

    private fun cleanupModel(cart: Minecart?) {
        if (cart == null) {
            return
        }
        val model = cartToModel.remove(cart.uniqueId)
        if (model != null) {
            modelToCart.remove(model.uniqueId)
            if (!model.isDead) {
                for (passenger in ArrayList(model.passengers)) {
                    passenger.leaveVehicle()
                }
                model.remove()
            }
        }
    }

    private fun warnInvalidEntityType(typeRaw: String?) {
        val display = typeRaw ?: ""
        if (display == lastWarnedInvalidType) {
            return
        }
        lastWarnedInvalidType = display
        plugin.logger.warning("EntityModel: invalid entity type '$display', skipping model attach")
    }

    private fun invokeBooleanSetter(cart: Minecart, methodName: String, value: Boolean) {
        try {
            cart.javaClass.getMethod(methodName, java.lang.Boolean.TYPE).invoke(cart, value)
        } catch (_: Exception) {
            // Optional server API.
        }
    }

    companion object {
        const val MINECART_ENTITY_TYPE: String = "MINECART"
        private const val DEFAULT_ENTITY_TYPE: String = "PIG"
        private const val MIN_ENTITY_MODEL_SPACING: Double = 0.8
        private const val ENTITY_MODEL_SPACING_PADDING: Double = 0.6

        @JvmStatic
        fun resolveEntityTypeRaw(preferredRaw: String?, fallbackRaw: String?): String =
            when {
                hasText(preferredRaw) -> preferredRaw.orEmpty().trim()
                hasText(fallbackRaw) -> fallbackRaw.orEmpty().trim()
                else -> ""
            }

        @JvmStatic
        fun resolveDefaultEntityTypeRaw(overrideRaw: String?, leashMobTypeRaw: String?): String {
            if (hasText(overrideRaw)) {
                return overrideRaw.orEmpty().trim()
            }
            val inherited = resolveEntityTypeRaw(null, leashMobTypeRaw)
            return if (parseEntityType(inherited) != null) inherited else DEFAULT_ENTITY_TYPE
        }

        @JvmStatic
        fun normalizeLineEntityType(raw: String?): String? {
            val normalized = normalizeConfiguredEntityName(raw) ?: return null
            if (normalized == MINECART_ENTITY_TYPE || normalized == "CART") {
                return MINECART_ENTITY_TYPE
            }
            return parseEntityType(normalized)?.name
        }

        @JvmStatic
        fun usesVisualEntity(raw: String?): Boolean {
            val normalized = normalizeLineEntityType(raw)
            return normalized != null && normalized != MINECART_ENTITY_TYPE
        }

        @JvmStatic
        fun recommendedSpacing(raw: String?, fallbackSpacing: Double): Double {
            val normalized = normalizeLineEntityType(raw)
            val fallback =
                if (fallbackSpacing.isFinite() && fallbackSpacing > 0) {
                    fallbackSpacing
                } else {
                    MIN_ENTITY_MODEL_SPACING
                }
            if (normalized == null || normalized == MINECART_ENTITY_TYPE) {
                return fallback
            }
            return maxOf(
                fallback,
                maxOf(MIN_ENTITY_MODEL_SPACING, estimatedEntitySpacing(normalized) + ENTITY_MODEL_SPACING_PADDING),
            )
        }

        @JvmStatic
        fun suggestedEntityTypeNames(): List<String> {
            val suggestions = ArrayList<String>()
            suggestions.add(MINECART_ENTITY_TYPE.lowercase(Locale.ROOT))
            for (type in EntityType.values()) {
                if (type.isAlive && type.entityClass != null) {
                    suggestions.add(type.name.lowercase(Locale.ROOT))
                }
            }
            suggestions.sort()
            return suggestions
        }

        private fun hasText(raw: String?): Boolean = raw != null && raw.trim().isNotEmpty()

        @JvmStatic
        fun parseEntityType(raw: String?): EntityType? {
            val normalized = normalizeConfiguredEntityName(raw) ?: return null
            return try {
                val type = EntityType.valueOf(normalized)
                if (!type.isAlive || type.entityClass == null) null else type
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        @JvmStatic
        fun normalizeConfiguredEntityName(raw: String?): String? {
            if (!hasText(raw)) {
                return null
            }
            var normalized = raw.orEmpty().trim()
            val namespacedSeparator = normalized.indexOf(':')
            if (namespacedSeparator >= 0 && namespacedSeparator + 1 < normalized.length) {
                normalized = normalized.substring(namespacedSeparator + 1)
            }
            normalized = normalized
                .replace('-', '_')
                .replace(' ', '_')
                .uppercase(Locale.ROOT)
            return normalized.ifEmpty { null }
        }

        private fun estimatedEntitySpacing(normalizedEntityType: String): Double =
            when (normalizedEntityType) {
                "RABBIT", "CHICKEN", "ALLAY", "BAT", "BEE", "ENDERMITE", "SILVERFISH",
                "TROPICAL_FISH", "COD", "SALMON", "PUFFERFISH",
                -> 1.0

                "CAT", "OCELOT", "FOX", "WOLF", "PIG", "SHEEP", "GOAT", "VILLAGER",
                "WANDERING_TRADER",
                -> 1.4

                "HORSE", "DONKEY", "MULE", "SKELETON_HORSE", "ZOMBIE_HORSE", "LLAMA",
                "TRADER_LLAMA", "CAMEL",
                -> 2.0

                "POLAR_BEAR", "PANDA", "HOGLIN", "ZOGLIN", "RAVAGER", "SNIFFER" -> 2.7
                "IRON_GOLEM", "WARDEN" -> 2.2
                "GHAST" -> 4.5
                "ENDER_DRAGON" -> 16.0
                else -> 1.6
            }
    }
}
