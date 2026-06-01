package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PartnershipTest {
    private Contract makePartnership(BigDecimal stakeA, BigDecimal stakeB, BigDecimal commission) {
        return Contract.createPartnership(
            UUID.randomUUID(), "Alice",
            UUID.randomUUID(), "Bob",
            stakeA, stakeB, commission,
            "build a city",
            "joint construction project",
            0L,
            10_000L
        );
    }

    @Test
    void startsInPendingAcceptWithBothApprove() {
        Contract c = makePartnership(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("5"));
        assertEquals(ContractStatus.PENDING_ACCEPT, c.status());
        assertEquals(ContractType.PARTNERSHIP, c.type());
        assertEquals(ResolutionRule.BOTH_APPROVE, c.resolutionRule());
        assertNull(c.arbiter());
    }

    @Test
    void asymmetricStakes() {
        Contract c = makePartnership(new BigDecimal("200"), new BigDecimal("50"), new BigDecimal("5"));
        assertEquals(new BigDecimal("200"),
            c.participant(ParticipantRole.PARTY_A).orElseThrow().moneyStake());
        assertEquals(new BigDecimal("50"),
            c.participant(ParticipantRole.PARTY_B).orElseThrow().moneyStake());
    }

    @Test
    void successPayoutsEachGetsBackOwnStakeMinusCommission() {
        Contract c = makePartnership(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.SUCCESS);

        BigDecimal aFromA = applyForRecipient(rules, ParticipantRole.PARTY_A, ParticipantRole.PARTY_A,
            new BigDecimal("100"));
        BigDecimal bFromB = applyForRecipient(rules, ParticipantRole.PARTY_B, ParticipantRole.PARTY_B,
            new BigDecimal("100"));
        BigDecimal sinkTotal = applyForSink(rules, new BigDecimal("100"));

        assertEquals(new BigDecimal("95.00"), aFromA);
        assertEquals(new BigDecimal("95.00"), bFromB);
        // commission 5% from each stake of 100 = 5 + 5 = 10
        assertEquals(new BigDecimal("10.00"), sinkTotal);
    }

    @Test
    void timeoutEachGetsFullStakeBack() {
        Contract c = makePartnership(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.TIMEOUT);
        assertEquals(2, rules.size());
        for (PayoutRule rule : rules) {
            assertEquals(rule.source(), rule.recipient().role());
            assertEquals(new BigDecimal("100.00"), rule.applyTo(new BigDecimal("100")));
        }
    }

    @Test
    void disputeResolvedForOwnerGivesAllToA() {
        Contract c = makePartnership(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER);

        BigDecimal toA = applyForRecipient(rules, ParticipantRole.PARTY_A, ParticipantRole.PARTY_A,
            new BigDecimal("100"))
            .add(applyForRecipient(rules, ParticipantRole.PARTY_B, ParticipantRole.PARTY_A,
                new BigDecimal("100")));
        assertEquals(new BigDecimal("200.00"), toA);
    }

    private BigDecimal applyForRecipient(List<PayoutRule> rules, ParticipantRole source,
                                          ParticipantRole recipient, BigDecimal stake) {
        BigDecimal total = BigDecimal.ZERO;
        for (PayoutRule rule : rules) {
            if (rule.source() == source
                && rule.recipient().kind() == PayoutRecipient.Kind.PARTICIPANT
                && rule.recipient().role() == recipient) {
                total = total.add(rule.applyTo(stake));
            }
        }
        return total;
    }

    private BigDecimal applyForSink(List<PayoutRule> rules, BigDecimal stake) {
        BigDecimal total = BigDecimal.ZERO;
        for (PayoutRule rule : rules) {
            if (rule.recipient().kind() == PayoutRecipient.Kind.SYSTEM_SINK) {
                total = total.add(rule.applyTo(stake));
            }
        }
        return total;
    }
}
