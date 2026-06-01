package org.cubexmc.metro.train;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Minecart;

final class TrainPassengerRegistry {

    private final Map<UUID, PassengerRecord> passengers = new HashMap<>();

    boolean add(HumanEntity player, Minecart cart) {
        if (player != null) {
            return passengers.put(player.getUniqueId(), new PassengerRecord(player, cart)) == null;
        }
        return false;
    }

    void remove(HumanEntity player) {
        if (player != null) {
            passengers.remove(player.getUniqueId());
        }
    }

    boolean contains(HumanEntity player) {
        return player != null && passengers.containsKey(player.getUniqueId());
    }

    boolean contains(UUID playerId) {
        return playerId != null && passengers.containsKey(playerId);
    }

    List<HumanEntity> onlinePassengers() {
        List<HumanEntity> result = new ArrayList<>();
        for (PassengerRecord record : passengers.values()) {
            if (record != null && record.player != null && isPlayerOnline(record.player)) {
                result.add(record.player);
            }
        }
        return result;
    }

    boolean hasOnlinePassengers() {
        for (PassengerRecord record : passengers.values()) {
            if (record != null && record.player != null && isPlayerOnline(record.player)) {
                return true;
            }
        }
        return false;
    }

    Minecart cartFor(HumanEntity player) {
        if (player == null) {
            return null;
        }
        PassengerRecord record = passengers.get(player.getUniqueId());
        return record != null ? record.cart : null;
    }

    Set<UUID> snapshotPassengerIds() {
        return new HashSet<>(passengers.keySet());
    }

    void clear() {
        passengers.clear();
    }

    private boolean isPlayerOnline(HumanEntity entity) {
        try {
            return (boolean) entity.getClass().getMethod("isOnline").invoke(entity);
        } catch (Exception e) {
            return false;
        }
    }

    private static final class PassengerRecord {
        private final HumanEntity player;
        private final Minecart cart;

        private PassengerRecord(HumanEntity player, Minecart cart) {
            this.player = player;
            this.cart = cart;
        }
    }
}
