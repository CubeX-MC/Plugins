package org.cubexmc.contract.service;

import org.cubexmc.contract.model.BatchRepeatPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRepeatRulesTest {
    private static final long HOUR = 60L * 60L * 1000L;

    @Test
    void unlimitedIgnoresActiveContractAndHistory() {
        BatchRepeatRules.Decision decision = BatchRepeatRules.evaluate(
            BatchRepeatPolicy.UNLIMITED,
            true,
            1_000L,
            2_000L,
            HOUR
        );

        assertTrue(decision.getAllowed());
    }

    @Test
    void onceAllowsOnlyFirstAcceptance() {
        assertTrue(BatchRepeatRules.evaluate(
            BatchRepeatPolicy.ONCE,
            false,
            null,
            2_000L,
            HOUR
        ).getAllowed());

        BatchRepeatRules.Decision active = BatchRepeatRules.evaluate(
            BatchRepeatPolicy.ONCE,
            true,
            null,
            2_000L,
            HOUR
        );
        assertFalse(active.getAllowed());
        assertEquals(BatchRepeatRules.BlockReason.ACTIVE_CONTRACT, active.getReason());

        BatchRepeatRules.Decision accepted = BatchRepeatRules.evaluate(
            BatchRepeatPolicy.ONCE,
            false,
            1_000L,
            2_000L,
            HOUR
        );
        assertFalse(accepted.getAllowed());
        assertEquals(BatchRepeatRules.BlockReason.ALREADY_ACCEPTED, accepted.getReason());
    }

    @Test
    void cooldownAllowsAcceptanceAfterInterval() {
        long acceptedAt = 10_000L;
        BatchRepeatRules.Decision waiting = BatchRepeatRules.evaluate(
            BatchRepeatPolicy.COOLDOWN,
            false,
            acceptedAt,
            acceptedAt + HOUR,
            2L * HOUR
        );
        assertFalse(waiting.getAllowed());
        assertEquals(BatchRepeatRules.BlockReason.COOLDOWN, waiting.getReason());
        assertEquals(HOUR, waiting.getRemainingMillis());

        BatchRepeatRules.Decision ready = BatchRepeatRules.evaluate(
            BatchRepeatPolicy.COOLDOWN,
            false,
            acceptedAt,
            acceptedAt + 2L * HOUR,
            2L * HOUR
        );
        assertTrue(ready.getAllowed());
    }
}
