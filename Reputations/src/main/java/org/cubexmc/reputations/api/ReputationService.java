package org.cubexmc.reputations.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Shared reputation service, registered with the Bukkit {@code ServicesManager} (Vault-style).
 * Consumer plugins obtain it via
 * {@code getServer().getServicesManager().load(ReputationService.class)}, register their fields
 * once on enable, then update values as gameplay happens. Mutation is keyed by {@code namespace:id}.
 */
public interface ReputationService {

    /** Registers (or replaces) a field. Call on enable; registration is not persisted. */
    void registerField(ReputationField field);

    Collection<ReputationField> fields();

    /** The field for a key, or {@code null} if no plugin registered it. */
    ReputationField field(String fieldKey);

    /** Current value, or the field's default value when nothing has been stored yet. */
    double get(UUID playerId, String fieldKey);

    void set(UUID playerId, String fieldKey, double value);

    /** Adds {@code delta} (may be negative) and returns the new value. */
    double add(UUID playerId, String fieldKey, double delta);

    /** Clears the stored value, so subsequent reads return the field default again. */
    void reset(UUID playerId, String fieldKey);

    ReputationProfile profile(UUID playerId);
}
