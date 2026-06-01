package org.cubexmc.contract.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Participant {
    private final ParticipantRole role;
    private UUID uuid;
    private String displayName;
    private final List<Asset> stake;

    public Participant(ParticipantRole role, UUID uuid, String displayName, List<Asset> stake) {
        this.role = role;
        this.uuid = uuid;
        this.displayName = displayName;
        this.stake = new ArrayList<>(stake);
    }

    public ParticipantRole role() {
        return role;
    }

    public UUID uuid() {
        return uuid;
    }

    public void uuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String displayName() {
        return displayName;
    }

    public void displayName(String displayName) {
        this.displayName = displayName;
    }

    public List<Asset> stake() {
        return List.copyOf(stake);
    }

    public BigDecimal moneyStake() {
        return stake.stream()
            .filter(Asset::isMoney)
            .map(Asset::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role.name());
        map.put("uuid", uuid == null ? null : uuid.toString());
        map.put("name", displayName);
        List<Map<String, Object>> stakeList = new ArrayList<>();
        for (Asset asset : stake) {
            stakeList.add(asset.toMap());
        }
        map.put("stake", stakeList);
        return map;
    }

    public static Participant fromMap(Map<?, ?> map) {
        ParticipantRole role = ParticipantRole.valueOf(Objects.toString(map.get("role"), "OWNER"));
        Object uuidRaw = map.get("uuid");
        UUID uuid = uuidRaw == null || uuidRaw.toString().isBlank() ? null : UUID.fromString(uuidRaw.toString());
        String name = map.get("name") == null ? null : map.get("name").toString();
        List<Asset> assets = new ArrayList<>();
        Object stakeRaw = map.get("stake");
        if (stakeRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> assetMap) {
                    assets.add(Asset.fromMap(assetMap));
                }
            }
        }
        return new Participant(role, uuid, name, assets);
    }
}
