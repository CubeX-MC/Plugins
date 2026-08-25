package org.cubexmc.reputations.api;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a player's effective reputation value changes.
 *
 * <p>The event may be asynchronous when the service mutation originated off the primary server
 * thread. Consumers must check {@link #isAsynchronous()} before touching Bukkit state. A reset that
 * only removes a stored value equal to the field default does not fire because the effective value
 * did not change.
 */
public final class ReputationChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String fieldKey;
    private final double previousValue;
    private final double newValue;
    private final ChangeType changeType;

    public ReputationChangeEvent(
            UUID playerId,
            String fieldKey,
            double previousValue,
            double newValue,
            ChangeType changeType,
            boolean asynchronous) {
        super(asynchronous);
        this.playerId = playerId;
        this.fieldKey = fieldKey;
        this.previousValue = previousValue;
        this.newValue = newValue;
        this.changeType = changeType;
    }

    public UUID playerId() {
        return playerId;
    }

    public String fieldKey() {
        return fieldKey;
    }

    public double previousValue() {
        return previousValue;
    }

    public double newValue() {
        return newValue;
    }

    public double delta() {
        return newValue - previousValue;
    }

    public ChangeType changeType() {
        return changeType;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum ChangeType {
        SET,
        ADD,
        RESET
    }
}
