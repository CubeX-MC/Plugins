package org.cubexmc.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class ItemBuilderTest {

    /**
     * ItemStack's constructor needs a live server, so it is stubbed. Each test gets the meta mock
     * the builder will operate on.
     */
    private MockedConstruction<ItemStack> stubItemStack(ItemMeta meta) {
        return mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(meta);
            when(stack.getMaxStackSize()).thenReturn(64);
        });
    }

    @Test
    void shouldApplyNameAndLoreThroughTheStyler() {
        ItemMeta meta = mock(ItemMeta.class);
        TextStyler upper = input -> "[" + input + "]";

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER, 1, upper)
                    .name("Title")
                    .lore("one", "two")
                    .build();
        }

        verify(meta).setDisplayName("[Title]");
        verify(meta).setLore(List.of("[one]", "[two]"));
    }

    @Test
    void shouldLeaveTextUntouchedWithTheDefaultStyler() {
        ItemMeta meta = mock(ItemMeta.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            // Text already rendered by cubex-i18n must not be processed a second time.
            new ItemBuilder(Material.PAPER).name("<already rendered>").build();
        }

        verify(meta).setDisplayName("<already rendered>");
    }

    @Test
    void shouldAccumulateLoreAndSkipNulls() {
        ItemMeta meta = mock(ItemMeta.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER)
                    .addLore("first")
                    .addLore((String) null)
                    .addEmptyLore()
                    .addLore(List.of("second"))
                    .build();
        }

        verify(meta).setLore(List.of("first", "", "second"));
    }

    @Test
    void shouldReplaceRatherThanAppendWhenLoreIsSetOutright() {
        ItemMeta meta = mock(ItemMeta.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER)
                    .addLore("stale")
                    .lore("fresh")
                    .build();
        }

        verify(meta).setLore(List.of("fresh"));
    }

    @Test
    void shouldNotWriteLoreWhenNoneWasSet() {
        ItemMeta meta = mock(ItemMeta.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER).name("bare").build();
        }

        // Writing an empty lore list would show a blank line under the name.
        verify(meta, never()).setLore(any());
    }

    @Test
    void glowShouldHideTheEnchantmentItAdds() {
        ItemMeta meta = mock(ItemMeta.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER).glow().build();
        }

        verify(meta).addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    @Test
    void shouldWritePersistentDataAndTheGuiMarker() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(container);
        NamespacedKey stringKey = mock(NamespacedKey.class);
        NamespacedKey intKey = mock(NamespacedKey.class);
        NamespacedKey markerKey = mock(NamespacedKey.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER)
                    .data(stringKey, "value")
                    .data(intKey, 7)
                    .guiMarker(markerKey)
                    .build();
        }

        verify(container).set(stringKey, PersistentDataType.STRING, "value");
        verify(container).set(intKey, PersistentDataType.INTEGER, 7);
        verify(container).set(markerKey, PersistentDataType.BYTE, (byte) 1);
    }

    @Test
    void shouldIgnoreNullKeysAndValuesInsteadOfThrowing() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(container);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER)
                    .data(null, "value")
                    .data(mock(NamespacedKey.class), (String) null)
                    .guiMarker(null)
                    .name(null)
                    .build();
        }

        verify(container, never()).set(any(), any(), any());
        verify(meta, never()).setDisplayName(any());
    }

    @Test
    void shouldSetSkullOwnerOnlyForSkullMeta() {
        SkullMeta skullMeta = mock(SkullMeta.class);
        OfflinePlayer player = mock(OfflinePlayer.class);

        try (MockedConstruction<ItemStack> ignored = stubItemStack(skullMeta)) {
            new ItemBuilder(Material.PLAYER_HEAD).skullOwner(player).build();
        }
        verify(skullMeta).setOwningPlayer(player);

        // A non-skull item silently ignores it rather than throwing.
        ItemMeta plainMeta = mock(ItemMeta.class);
        try (MockedConstruction<ItemStack> ignored = stubItemStack(plainMeta)) {
            new ItemBuilder(Material.PAPER).skullOwner(player).build();
        }
    }

    @Test
    void shouldClampAmountIntoTheStackLimit() {
        ItemMeta meta = mock(ItemMeta.class);

        try (MockedConstruction<ItemStack> construction = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER).amount(999).build();
            ItemStack stack = construction.constructed().get(0);
            verify(stack).setAmount(64);
        }

        try (MockedConstruction<ItemStack> construction = stubItemStack(meta)) {
            new ItemBuilder(Material.PAPER).amount(0).build();
            ItemStack stack = construction.constructed().get(0);
            verify(stack).setAmount(1);
        }
    }

    @Test
    void shouldStillReturnAStackWhenTheItemHasNoMeta() {
        // Material.AIR has no ItemMeta; the builder must not NPE on it.
        try (MockedConstruction<ItemStack> construction = stubItemStack(null)) {
            ItemStack built = new ItemBuilder(Material.AIR).name("ignored").lore("ignored").build();
            assertSame(construction.constructed().get(0), built);
        }
    }
}
