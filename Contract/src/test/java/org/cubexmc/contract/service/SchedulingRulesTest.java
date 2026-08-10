package org.cubexmc.contract.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cubexmc.contract.model.ContractStatus;
import org.junit.jupiter.api.Test;

class SchedulingRulesTest {
    @Test
    void deadlineStartsAtPublicationTime() {
        assertEquals(1_000L + 2 * SchedulingRules.DAY_MILLIS,
                SchedulingRules.expiryAt(100L, 1_000L, 2));
        assertEquals(100L + SchedulingRules.DAY_MILLIS,
                SchedulingRules.expiryAt(100L, null, 1));
    }

    @Test
    void activationIsDueAndIdempotentByStatus() {
        assertFalse(SchedulingRules.shouldActivate(ContractStatus.SCHEDULED, 1_001L, 1_000L));
        assertTrue(SchedulingRules.shouldActivate(ContractStatus.SCHEDULED, 1_000L, 1_000L));
        assertFalse(SchedulingRules.shouldActivate(ContractStatus.OPEN, 1_000L, 1_000L));
        assertFalse(SchedulingRules.shouldActivate(ContractStatus.SCHEDULED, null, 1_000L));
    }
}

