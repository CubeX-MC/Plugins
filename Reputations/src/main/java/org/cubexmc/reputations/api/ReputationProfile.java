package org.cubexmc.reputations.api;

import java.util.Map;
import java.util.UUID;

/** Read-only snapshot of one player's reputation values. */
public interface ReputationProfile {

    UUID playerId();

    /** Stored value for the field, or the field's default (0 if the field is unknown). */
    double value(String fieldKey);

    /** Stored values only (fields never written for this player are absent). */
    Map<String, Double> values();
}
