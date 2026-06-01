package org.cubexmc.metro.physics;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.util.Vector;

final class ReactiveCartStateStore {

    private final Map<UUID, CartState> states = new HashMap<>();

    void clear() {
        states.clear();
    }

    void retainAll(Set<UUID> validIds) {
        states.keySet().retainAll(validIds);
    }

    void rememberVelocity(UUID cartId, Vector velocity) {
        if (cartId == null || velocity == null) {
            return;
        }
        stateFor(cartId).commandedVelocity = velocity.clone();
    }

    void rememberDirection(UUID cartId, Vector direction) {
        if (cartId == null || direction == null || direction.lengthSquared() < 1.0e-8) {
            return;
        }
        stateFor(cartId).lastDirection = direction.clone().normalize();
    }

    void rememberPosition(UUID cartId, Location position) {
        if (cartId == null || position == null) {
            return;
        }
        stateFor(cartId).commandedPosition = position.clone();
    }

    Vector getVelocity(UUID cartId) {
        CartState state = states.get(cartId);
        return state != null && state.commandedVelocity != null ? state.commandedVelocity.clone() : null;
    }

    Vector getDirection(UUID cartId) {
        CartState state = states.get(cartId);
        return state != null && state.lastDirection != null ? state.lastDirection.clone() : null;
    }

    Location getPosition(UUID cartId) {
        CartState state = states.get(cartId);
        return state != null && state.commandedPosition != null ? state.commandedPosition.clone() : null;
    }

    int size() {
        return states.size();
    }

    private CartState stateFor(UUID cartId) {
        return states.computeIfAbsent(cartId, ignored -> new CartState());
    }

    private static final class CartState {
        private Vector commandedVelocity;
        private Vector lastDirection;
        private Location commandedPosition;
    }
}