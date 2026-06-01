package org.cubexmc.metro.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class EntityModelControllerTest {

    @Test
    void shouldUseLeashMobTypeWhenEntityModelOverrideIsBlank() {
        assertEquals("PIG", EntityModelController.resolveEntityTypeRaw("", "PIG"));
        assertEquals("PIG", EntityModelController.resolveEntityTypeRaw("  ", " PIG "));
    }

    @Test
    void shouldPreferExplicitEntityModelOverride() {
        assertEquals("COW", EntityModelController.resolveEntityTypeRaw(" COW ", "PIG"));
    }

    @Test
    void shouldFallBackToPigWhenInheritedEntityTypeIsUnsupported() {
        assertEquals("PIG", EntityModelController.resolveDefaultEntityTypeRaw("", "NOT_A_MOB"));
    }

    @Test
    void shouldPreserveExplicitInvalidOverrideForWarning() {
        assertEquals("NOT_A_MOB", EntityModelController.resolveDefaultEntityTypeRaw("NOT_A_MOB", "PIG"));
    }

    @Test
    void shouldParseLivingEntityTypesCaseInsensitively() {
        assertEquals(EntityType.PIG, EntityModelController.parseEntityType("pig"));
    }

    @Test
    void shouldRejectBlankEntityTypes() {
        assertNull(EntityModelController.parseEntityType(""));
    }

    @Test
    void shouldNormalizeMinecartAndLivingLineEntityTypes() {
        assertEquals("MINECART", EntityModelController.normalizeLineEntityType("minecart"));
        assertEquals("PIG", EntityModelController.normalizeLineEntityType("minecraft:pig"));
        assertNull(EntityModelController.normalizeLineEntityType("not_a_mob"));
    }

    @Test
    void shouldRecommendLargerSpacingForLargeEntityModels() {
        assertEquals(1.6, EntityModelController.recommendedSpacing("minecart", 1.6));
        assertEquals(2.6, EntityModelController.recommendedSpacing("horse", 1.6));
        assertEquals(5.1, EntityModelController.recommendedSpacing("ghast", 1.6));
    }
}
