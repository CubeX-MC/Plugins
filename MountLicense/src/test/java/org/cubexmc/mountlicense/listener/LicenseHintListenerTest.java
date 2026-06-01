package org.cubexmc.mountlicense.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.cubexmc.mountlicense.service.ItemFactory;
import org.junit.jupiter.api.Test;

class LicenseHintListenerTest {

    @Test
    void licenseInMainHandShowsActionBarForRegisterPlayer() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        LanguageManager lang = mock(LanguageManager.class);
        Player player = player(true);
        ItemStack license = mock(ItemStack.class);

        when(itemFactory.isLicense(license)).thenReturn(true);

        LicenseHintListener listener = new LicenseHintListener(plugin, itemFactory, lang);
        listener.maybeShowHint(player, license);

        verify(lang).sendActionBar(player, "license_item.hint_actionbar");
    }

    @Test
    void hintIsSkippedWithoutRegisterPermission() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        LanguageManager lang = mock(LanguageManager.class);
        Player player = player(false);
        ItemStack license = mock(ItemStack.class);

        when(itemFactory.isLicense(license)).thenReturn(true);

        LicenseHintListener listener = new LicenseHintListener(plugin, itemFactory, lang);
        listener.maybeShowHint(player, license);

        verify(lang, never()).sendActionBar(player, "license_item.hint_actionbar");
    }

    @Test
    void hintIsSkippedForNonLicenseItem() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        LanguageManager lang = mock(LanguageManager.class);
        Player player = player(true);
        ItemStack otherItem = mock(ItemStack.class);

        when(itemFactory.isLicense(otherItem)).thenReturn(false);

        LicenseHintListener listener = new LicenseHintListener(plugin, itemFactory, lang);
        listener.maybeShowHint(player, otherItem);

        verify(lang, never()).sendActionBar(player, "license_item.hint_actionbar");
    }

    private static Player player(boolean canRegister) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("mountlicense.register")).thenReturn(canRegister);
        return player;
    }
}
