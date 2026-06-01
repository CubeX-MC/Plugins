package org.cubexmc.contract.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Asset {
    private final AssetKind kind;
    private final BigDecimal amount;
    private final String reference;

    private Asset(AssetKind kind, BigDecimal amount, String reference) {
        this.kind = kind;
        this.amount = amount;
        this.reference = reference;
    }

    public static Asset money(BigDecimal amount) {
        return new Asset(AssetKind.MONEY, amount, null);
    }

    public static Asset item(String reference) {
        return new Asset(AssetKind.ITEM, BigDecimal.ZERO, reference);
    }

    public static Asset landPermission(String reference) {
        return new Asset(AssetKind.LAND_PERMISSION, BigDecimal.ZERO, reference);
    }

    public AssetKind kind() {
        return kind;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String reference() {
        return reference;
    }

    public boolean isMoney() {
        return kind == AssetKind.MONEY;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind.name());
        if (kind == AssetKind.MONEY) {
            map.put("amount", amount.toPlainString());
        } else {
            map.put("reference", reference);
        }
        return map;
    }

    public static Asset fromMap(Map<?, ?> map) {
        AssetKind kind = AssetKind.valueOf(Objects.toString(map.get("kind"), "MONEY"));
        if (kind == AssetKind.MONEY) {
            Object raw = map.get("amount");
            BigDecimal value = raw == null ? BigDecimal.ZERO : new BigDecimal(raw.toString());
            return money(value);
        }
        String ref = Objects.toString(map.get("reference"), "");
        return new Asset(kind, BigDecimal.ZERO, ref);
    }
}
