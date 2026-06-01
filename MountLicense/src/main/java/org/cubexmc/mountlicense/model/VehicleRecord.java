package org.cubexmc.mountlicense.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class VehicleRecord {

    private final UUID vehicleId;
    private final UUID entityUuid;
    private final String entityType;
    private final UUID ownerUuid;
    private final String profile;

    private String plate;
    private String displayName;
    private VehicleState state;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private String stationId;
    private long createdAt;
    private long lastSeenAt;
    private final Set<UUID> trustees = Collections.synchronizedSet(new LinkedHashSet<>());

    public VehicleRecord(UUID vehicleId, UUID entityUuid, String entityType,
                         UUID ownerUuid, String profile) {
        this.vehicleId = Objects.requireNonNull(vehicleId);
        this.entityUuid = Objects.requireNonNull(entityUuid);
        this.entityType = entityType;
        this.ownerUuid = Objects.requireNonNull(ownerUuid);
        this.profile = profile;
        this.plate = defaultPlate(vehicleId);
        this.state = VehicleState.ACTIVE;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public UUID vehicleId() { return vehicleId; }
    public UUID entityUuid() { return entityUuid; }
    public String entityType() { return entityType; }
    public UUID ownerUuid() { return ownerUuid; }
    public String profile() { return profile; }

    public String plate() { return plate; }
    public void setPlate(String plate) { this.plate = normalizePlate(plate, vehicleId); }

    public String displayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public VehicleState state() { return state; }
    public void setState(VehicleState state) { this.state = state == null ? VehicleState.ACTIVE : state; }

    public String world() { return world; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    public void setLocation(String world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public String stationId() { return stationId; }
    public void setStationId(String stationId) { this.stationId = stationId; }

    public long createdAt() { return createdAt; }
    public void setCreatedAt(long t) { this.createdAt = t; }

    public long lastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(long t) { this.lastSeenAt = t; }

    public String shortId() {
        return plate;
    }

    public Set<UUID> trustees() {
        return trustees;
    }

    public boolean addTrustee(UUID playerUuid) {
        if (playerUuid == null) return false;
        if (playerUuid.equals(ownerUuid)) return false;
        return trustees.add(playerUuid);
    }

    public boolean removeTrustee(UUID playerUuid) {
        if (playerUuid == null) return false;
        return trustees.remove(playerUuid);
    }

    public boolean isTrustee(UUID playerUuid) {
        return playerUuid != null && trustees.contains(playerUuid);
    }

    private static String normalizePlate(String raw, UUID fallbackId) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultPlate(fallbackId);
        }
        return raw.trim().toUpperCase();
    }

    private static String defaultPlate(UUID vehicleId) {
        String compact = vehicleId.toString().replace("-", "").toUpperCase();
        return compact.substring(0, 3) + "-" + compact.substring(3, 6);
    }
}
