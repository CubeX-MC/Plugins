package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTest {
    @Test
    void moneyAssetRoundTrip() {
        Asset original = Asset.money(new BigDecimal("123.45"));
        Asset restored = Asset.fromMap(original.toMap());
        assertEquals(AssetKind.MONEY, restored.kind());
        assertTrue(restored.isMoney());
        assertEquals(new BigDecimal("123.45"), restored.amount());
    }

    @Test
    void itemAssetRoundTrip() {
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
