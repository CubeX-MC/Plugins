package org.cubexmc.mountlicense.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.EnumSet;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.config.ConfigManager;
import org.cubexmc.mountlicense.config.ProfileRegistry;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.model.VehicleFeature;
import org.cubexmc.mountlicense.model.VehicleProfile;
import org.cubexmc.mountlicense.persistence.VehicleIndex;
import org.junit.jupiter.api.Test;

class RegistryServiceTest {

    @Test
    void profileRequiringSaddleRejectsUnsaddledSteerableEntity() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        PdcKeys keys = mock(PdcKeys.class);
        VehicleIndex index = mock(VehicleIndex.class);
        ProfileRegistry profiles = mock(ProfileRegistry.class);
        LanguageManager lang = mock(LanguageManager.class);
        ConfigManager config = mock(ConfigManager.class);
        ItemFactory itemFactory = mock(ItemFactory.class);

        Player player = mock(Player.class);
        ItemStack license = mock(ItemStack.class);
        Entity pig = mock(Entity.class, withSettings().extraInterfaces(Steerable.class));
        Steerable steerable = (Steerable) pig;

        VehicleProfile pigProfile = new VehicleProfile(
                "pig",
                EnumSet.of(EntityType.PIG),
                EnumSet.of(VehicleFeature.REGISTER),
                false,
                true
        );

        when(plugin.configManager()).thenReturn(config);
        when(plugin.itemFactory()).thenReturn(itemFactory);
        when(config.getRegisterCooldownSeconds()).thenReturn(0);
        when(config.isRejectAlreadyRegistered()).thenReturn(false);
        when(config.getMaxVehiclesPerPlayer()).thenReturn(-1);
        when(itemFactory.isLicense(license)).thenReturn(true);
        when(player.hasPermission("mountlicense.register")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(pig.getType()).thenReturn(EntityType.PIG);
        when(steerable.hasSaddle()).thenReturn(false);
        when(profiles.byEntityType(EntityType.PIG)).thenReturn(pigProfile);

        RegistryService service = new RegistryService(plugin, keys, index, profiles, lang);

        assertEquals(RegistryService.Result.REQUIRES_SADDLE,
                service.tryRegister(player, pig, license));
        verify(lang).send(player, "registration.fail_requires_saddle", null);
    }
}
