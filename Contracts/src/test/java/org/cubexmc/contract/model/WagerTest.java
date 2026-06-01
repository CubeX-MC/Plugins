package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WagerTest {
    private Contract makeWager(BigDecimal stake, BigDecimal commission) {
        return Contract.createWager(
            UUID.randomUUID(), "Alice",
            UUID.randomUUID(), "Bob",
            UUID.randomUUID(), "Charlie",
            "duel",
            "first to win three rounds",
            stake,
            commission,
            0L,
            10_000L
        );
    }

    @Test
    void wagerStartsInPendingAccept() {
        Contract c = makeWager(new BigDecimal("100"), new BigDecimal("5"));
        assertEquals(ContractStatus.PENDING_ACCEPT, c.status());
        assertEquals(ContractType.WAGER, c.type());
        assertEquals(ResolutionRule.ARBITER, c.resolutionRule());
    }

    @Test
    void wagerHasBothPartiesAndArbiter() {
        Contract c = makeWager(new BigDecimal("100"), new BigDecimal("5"));
        assertEquals(2, c.participants().size());
        assertTrue(c.participant(ParticipantRole.PARTY_A).isPresent());
        assertTrue(c.participant(ParticipantRole.PARTY_B).isPresent());
        assertNotNull(c.arbiter());
        assertEquals(ParticipantRole.MEDIATOR, c.arbiter().role());
    }

    @Test
    void wagerStakesAreEqual() {
        Contract c = makeWager(new BigDecimal("250"), new BigDecimal("5"));
        BigDecimal a = c.participant(ParticipantRole.PARTY_A).orElseThrow().moneyStake();
        BigDecimal b = c.participant(ParticipantRole.PARTY_B).orElseThrow().moneyStake();
        assertEquals(new BigDecimal("250"), a);
        assertEquals(new BigDecimal("250"), b);
    }

    @Test
    void aWinsPayoutSumsToFullStakePoolMinusCommission() {
        Contract c = makeWager(new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.DISPUTE_RESOLVED_FOR_OWNER);

        BigDecimal toPartyA = BigDecimal.ZERO;
        BigDecimal toSink = BigDecimal.ZERO;
        for (PayoutRule rule : rules) {
            BigDecimal share = rule.applyTo(new BigDecimal("100"));
            if (rule.recipient().kind() == PayoutRecipient.Kind.PARTICIPANT
                && rule.recipient().role() == ParticipantRole.PARTY_A) {
                toPartyA = toPartyA.add(share);
            } else if (rule.recipient().kind() == PayoutRecipient.Kind.SYSTEM_SINK) {
                toSink = toSink.add(share);
            }
        }
        assertEquals(new BigDecimal("190.00"), toPartyA);
        assertEquals(new BigDecimal("10.00"), toSink);
    }

    @Test
    void bWinsMirror() {
        Contract c = makeWager(new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.DISPUTE_RESOLVED_FOR_CONTRACTOR);

        BigDecimal toPartyB = BigDecimal.ZERO;
        for (PayoutRule rule : rules) {
            BigDecimal share = rule.applyTo(new BigDecimal("100"));
            if (rule.recipient().kind() == PayoutRecipient.Kind.PARTICIPANT
                && rule.recipient().role() == ParticipantRole.PARTY_B) {
                toPartyB = toPartyB.add(share);
            }
        }
        assertEquals(new BigDecimal("190.00"), toPartyB);
    }

    @Test
    void timeoutRefundsEachToSelfNoCommission() {
        Contract c = makeWager(new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.TIMEOUT);
        assertEquals(2, rules.size());
        for (PayoutRule rule : rules) {
            // source 和 recipient role 应该相同(各退各的)
            assertEquals(PayoutRecipient.Kind.PARTICIPANT, rule.recipient().kind());
            assertEquals(rule.source(), rule.recipient().role());
            assertEquals(new BigDecimal("100.00"), rule.applyTo(new BigDecimal("100")));
        }
    }

    @Test
    void failureOnlyRefundsPartyA() {
        // 当 opponent 拒绝接受,PARTY_B 还没押注,只退 PARTY_A
        Contract c = makeWager(new BigDecimal("100"), new BigDecimal("5"));
        List<PayoutRule> rules = c.payoutsFor(PayoutCondition.FAILURE);
        assertEquals(1, rules.size());
        PayoutRule rule = rules.get(0);
        assertEquals(ParticipantRole.PARTY_A, rule.source());
        assertEquals(ParticipantRole.PARTY_A, rule.recipient().role());
        assertEquals(new BigDecimal("100.00"), rule.applyTo(new BigDecimal("100")));
    }
}
