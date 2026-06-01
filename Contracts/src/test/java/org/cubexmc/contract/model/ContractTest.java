package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractTest {
    private Contract makeContract(BigDecimal reward, BigDecimal commissionPercent, long expiresAt) {
        return Contract.createService(
            UUID.randomUUID(),
            "Alice",
            "title",
            "desc",
            reward,
            new BigDecimal("20"),
            commissionPercent,
            0L,
            expiresAt
        );
    }

    @Test
    void commissionAndPayoutUseHalfUp() {
        Contract c = makeContract(new BigDecimal("100"), new BigDecimal("5"), 1_000L);
        assertEquals(new BigDecimal("5.00"), c.commissionAmount());
        assertEquals(new BigDecimal("95.00"), c.payoutAmount());
    }

    @Test
    void commissionHandlesFractionalReward() {
        Contract c = makeContract(new BigDecimal("33.33"), new BigDecimal("5"), 1_000L);
        assertEquals(new BigDecimal("1.67"), c.commissionAmount());
        assertEquals(new BigDecimal("31.66"), c.payoutAmount());
    }

    @Test
    void zeroCommission() {
        Contract c = makeContract(new BigDecimal("100"), BigDecimal.ZERO, 1_000L);
        assertEquals(new BigDecimal("0.00"), c.commissionAmount());
        assertEquals(new BigDecimal("100.00"), c.payoutAmount());
    }

    @Test
    void isExpiredAtExpiryBoundary() {
        Contract c = makeContract(new BigDecimal("100"), new BigDecimal("5"), 1_000L);
        assertFalse(c.isExpired(999L));
        assertTrue(c.isExpired(1_000L));
        assertTrue(c.isExpired(2_000L));
    }

    @Test
    void isNotExpiredWhenFinal() {
        Contract c = makeContract(new BigDecimal("100"), new BigDecimal("5"), 1_000L);
        c.status(ContractStatus.COMPLETED);
        assertFalse(c.isExpired(5_000L));
        c.status(ContractStatus.DISPUTED);
        assertFalse(c.isExpired(5_000L));
    }

    @Test
    void shortIdReturnsFirstEight() {
        Contract c = makeContract(new BigDecimal("100"), new BigDecimal("5"), 1_000L);
        assertEquals(8, c.shortId().length());
        assertTrue(c.id().startsWith(c.shortId()));
    }

    @Test
    void genericParticipantAliasesCoverWagerInvites() {
        UUID partyA = UUID.randomUUID();
        UUID partyB = UUID.randomUUID();
        UUID arbiter = UUID.randomUUID();
        Contract c = Contract.createWager(
            partyA, "Alice",
            partyB, "Bob",
            arbiter, "Cara",
            "title",
            "desc",
            new BigDecimal("100"),
            new BigDecimal("5"),
            0L,
            1_000L
        );

        assertEquals(partyA, c.ownerUuid());
        assertEquals(partyB, c.contractorUuid());
        assertTrue(c.relatedTo(partyA));
        assertTrue(c.relatedTo(partyB));
        assertTrue(c.relatedTo(arbiter));
    }

    @Test
    void genericParticipantAliasesCoverPartnershipInvites() {
        UUID partyA = UUID.randomUUID();
        UUID partyB = UUID.randomUUID();
        Contract c = Contract.createPartnership(
            partyA, "Alice",
            partyB, "Bob",
            new BigDecimal("100"),
            new BigDecimal("150"),
            new BigDecimal("5"),
            "title",
            "desc",
            0L,
            1_000L
        );

        assertEquals(partyA, c.ownerUuid());
        assertEquals(partyB, c.contractorUuid());
        assertTrue(c.relatedTo(partyA));
        assertTrue(c.relatedTo(partyB));
    }

    @Test
    void optionalMediatorIsRelatedAndTracksAcceptance() {
        UUID owner = UUID.randomUUID();
        UUID mediator = UUID.randomUUID();
        Contract c = Contract.createService(
            owner,
            "Alice",
            "title",
            "desc",
            new BigDecimal("100"),
            new BigDecimal("20"),
            new BigDecimal("5"),
            0L,
            1_000L
        );

        c.arbiter(new Participant(ParticipantRole.MEDIATOR, mediator, "Cara", java.util.List.of()));
        c.arbiterAccepted(false);

        assertTrue(c.hasArbiter());
        assertTrue(c.relatedTo(mediator));
        assertFalse(c.arbiterAccepted());

        c.arbiterAccepted(true);

        assertTrue(c.arbiterAccepted());
    }
}
