package org.cubexmc.reputations.api;

import java.util.UUID;

/** Test fixture matching the optional provider's public reflection surface. */
public interface ReputationService {
    void registerField(ReputationField field);

    double add(UUID playerId, String fieldKey, double delta);
}
