package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AssetTest {
    private ItemStack stack(Material material, int amount, Map<String, Object> serialized) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        when(stack.serialize()).thenReturn(serialized);
        return stack;
    }

    @Test
    void moneyAssetRoundTrip() {
        Asset original = Asset.money(new BigDecimal("123.45"));
        Asset restored = Asset.fromMap(original.toMap());
        assertEquals(AssetKind.MONEY, restored.kind());
        assertTrue(restored.isMoney());
        assertEquals(new BigDecimal("123.45"), restored.amount());
        // Money carries no stack, so item accessors stay empty rather than throwing.
        assertNull(restored.itemStack());
        assertEquals(0, restored.itemCount());
    }

    @Test
    void itemAssetKeepsTheRealStackAcrossARoundTrip() {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("type", "DIAMOND");
        serialized.put("amount", 8);
        ItemStack diamonds = stack(Material.DIAMOND, 8, serialized);

        Asset original = Asset.item(diamonds);
        assertEquals("DIAMOND x 8", original.reference());
        assertEquals(8, original.itemCount());

        Map<String, Object> map = (Map<String, Object>) original.toMap();
        assertEquals(serialized, map.get("item"));

        try (MockedStatic<ItemStack> statics = mockStatic(ItemStack.class)) {
            statics.when(() -> ItemStack.deserialize(anyMap())).thenReturn(diamonds);
            Asset restored = Asset.fromMap(map);
            assertEquals(AssetKind.ITEM, restored.kind());
            assertEquals("DIAMOND x 8", restored.reference());
            assertEquals(8, restored.itemCount());
        }
    }

    @Test
    void legacyDisplayOnlyItemAssetsStillLoad() {
        // Contracts written before assets carried stacks only stored the label.
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("kind", "ITEM");
        legacy.put("reference", "DIAMOND x 64");

        Asset restored = Asset.fromMap(legacy);
        assertEquals(AssetKind.ITEM, restored.kind());
        assertEquals("DIAMOND x 64", restored.reference());
        // No stack was stored, so nothing is invented for it.
        assertNull(restored.itemStack());
        assertEquals(0, restored.itemCount());
    }

    @Test
    void unreadableItemPayloadFallsBackToTheLabelInsteadOfFailingTheLoad() {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("kind", "ITEM");
        broken.put("reference", "DIAMOND x 64");
        broken.put("item", "not-a-map");

        Asset restored = Asset.fromMap(broken);
        assertEquals("DIAMOND x 64", restored.reference());
        assertNull(restored.itemStack());
    }

    @Test
    void itemAssetRoundTripFromADisplayReference() {
        Asset original = Asset.item("DIAMOND x 64");
        Asset restored = Asset.fromMap(original.toMap());
        assertEquals(AssetKind.ITEM, restored.kind());
        assertEquals("DIAMOND x 64", restored.reference());
    }

    @Test
    void landPermissionAsset() {
        Asset asset = Asset.landPermission("world:lands:claim-123");
        assertEquals(AssetKind.LAND_PERMISSION, asset.kind());
        assertEquals("world:lands:claim-123", asset.reference());
    }
}
