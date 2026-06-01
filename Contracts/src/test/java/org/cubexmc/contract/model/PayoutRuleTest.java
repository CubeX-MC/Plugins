package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayoutRuleTest {
    @Test
    void appliesPercentToSource() {
        PayoutRule rule = new PayoutRule(
            PayoutCondition.SUCCESS,
            ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.CONTRACTOR),
            new BigDecimal("95")
        );
        assertEquals(new BigDecimal("95.00"), rule.applyTo(new BigDecimal("100")));
    }

    @Test
    void applyTo100Pct() {
        PayoutRule rule = new PayoutRule(
            PayoutCondition.FAILURE,
            ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.OWNER),
            new BigDecimal("100")
        );
        assertEquals(new BigDecimal("250.00"), rule.applyTo(new BigDecimal("250")));
    }

    @Test
    void roundTrip() {
        PayoutRule original = new PayoutRule(
            PayoutCondition.SUCCESS,
            ParticipantRole.OWNER,
            PayoutRecipient.systemSink(),
            new BigDecimal("5")
        );
        PayoutRule restored = PayoutRule.fromMap(original.toMap());
        assertEquals(original.condition(), restored.condition());
        assertEquals(original.source(), restored.source());
        assertEquals(original.recipient().kind(), restored.recipient().kind());
        assertEquals(new BigDecimal("5"), restored.sharePercent());
    }

    @Test
    void roundTripWithParticipantRecipient() {
        PayoutRule original = new PayoutRule(
            PayoutCondition.TIMEOUT,
            ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.CONTRACTOR),
            new BigDecimal("100")
        );
        PayoutRule restored = PayoutRule.fromMap(original.toMap());
        assertEquals(PayoutRecipient.Kind.PARTICIPANT, restored.recipient().kind());
        assertEquals(ParticipantRole.CONTRACTOR, restored.recipient().role());
    }
}
