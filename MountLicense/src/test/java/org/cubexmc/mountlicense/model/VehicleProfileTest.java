package org.cubexmc.mountlicense.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class VehicleProfileTest {

    @Test
    void emptyEntitySetCreatesDisabledProfileInsteadOfThrowing() {
        VehicleProfile profile = new VehicleProfile(
                "future",
                EnumSet.noneOf(EntityType.class),
                EnumSet.of(VehicleFeature.REGISTER),
                false,
                true
        );

        assertTrue(profile.entityTypes().isEmpty());
        assertFalse(profile.matches(EntityType.HORSE));
        assertTrue(profile.has(VehicleFeature.REGISTER));
        assertTrue(profile.requiresSaddle());
    }
}
