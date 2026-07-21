package org.cubexmc.contract.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void isExpiredOnlyWhileAwaitingAcceptance() {
        Contract c = makeContract(new BigDecimal("100"), new BigDecimal("5"), 1_000L);
        c.status(ContractStatus.IN_PROGRESS);
        assertFalse(c.isExpired(5_000L));

        c.status(ContractStatus.SUBMITTED);
        assertFalse(c.isExpired(5_000L));

        c.status(ContractStatus.DISPUTED);
        assertFalse(c.isExpired(5_000L));

        c.status(ContractStatus.COMPLETED);
        assertFalse(c.isExpired(5_000L));

        c.status(ContractStatus.PENDING_ACCEPT);
        assertTrue(c.isExpired(5_000L));
    }

    @Test
    void isNotExpiredWhenFinal() {
        Contract c = makeContract(new BigDecimal("100"), new BigDecimal("5"), 1_000L);
        c.status(ContractStatus.COMPLETED);
        assertFalse(c.isExpired(5_000L));
        c.status(ContractStatus.CANCELLED);
        assertFalse(c.isExpired(5_000L));
        c.status(ContractStatus.EXPIRED);
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

    @Test
    void serviceObjectiveSwitchesToSystemVerification() {
        ContractObjective objective = ContractObjective.of(ObjectiveType.KILL_ENTITY, "zombie", 3);
        Contract c = Contract.createService(
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            "Alice",
            "title",
            "desc",
            new BigDecimal("100"),
            new BigDecimal("20"),
            new BigDecimal("5"),
            0L,
            1_000L,
            objective
        );

        assertEquals(ResolutionRule.SYSTEM_OBJECTIVE, c.resolutionRule());
        assertTrue(c.systemVerifiedService());
        assertEquals(ObjectiveType.KILL_ENTITY, c.objective().type());
        assertEquals("ZOMBIE", c.objective().target());
        assertEquals("0/3", c.objective().progressText());
    }

    @Test
    void serviceCanEscrowItemReward() {
        ItemStack rewardItem = mock(ItemStack.class);
        when(rewardItem.clone()).thenReturn(rewardItem);
        when(rewardItem.getType()).thenReturn(Material.DIAMOND);
        when(rewardItem.getAmount()).thenReturn(8);

        Contract c = Contract.createService(
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            "Alice",
            "title",
            "desc",
            BigDecimal.ZERO,
            List.of(rewardItem),
            new BigDecimal("20"),
            new BigDecimal("5"),
            0L,
            1_000L,
            ContractObjective.of(ObjectiveType.DELIVER_MONEY, "money", 250)
        );

        assertEquals(BigDecimal.ZERO, c.reward());
        assertTrue(c.hasRewardItems());
        assertEquals(8, c.rewardItemCount());
        assertEquals(ObjectiveType.DELIVER_MONEY, c.objective().type());
        assertEquals("MONEY", c.objective().target());
    }

    @Test
    void objectiveMaterialTargetsNormalizeBukkitAliases() {
        ContractObjective objective = ContractObjective.of(ObjectiveType.CONSUME_ITEM, "bread", 2);

        assertEquals("BREAD", objective.target());
        assertTrue(objective.matches("BREAD"));
    }

    @Test
    void objectiveCommandTargetMatchesCommandArguments() {
        ContractObjective objective = ContractObjective.of(ObjectiveType.RUN_COMMAND, "/spawn", 1);

        assertTrue(objective.matches("/spawn"));
        assertTrue(objective.matches("/spawn home"));
        assertFalse(objective.matches("/spawnpoint"));
    }

    @Test
    void objectiveBlankChatTargetBecomesAny() {
        ContractObjective objective = ContractObjective.of(ObjectiveType.CHAT, "", 1);

        assertEquals("ANY", objective.target());
        assertTrue(objective.matches("hello"));
    }

    @Test
    void objectiveChatTargetPreservesCaseAndMatchesExactText() {
        ContractObjective objective = ContractObjective.of(ObjectiveType.CHAT, "  Hello World  ", 1);

        assertEquals("Hello World", objective.target());
        assertTrue(objective.matches("Hello World"));
        assertTrue(objective.matches("  Hello World  "));
        assertFalse(objective.matches("hello world"));
        assertFalse(objective.matches("HELLO WORLD"));
        assertFalse(objective.matches("Hello  World"));
        assertFalse(objective.matches("Hello World!"));
    }

    @Test
    void objectiveStoredChatTargetKeepsCaseSensitiveSemantics() {
        ContractObjective restored = ContractObjective.fromMap(
            java.util.Map.of("type", "CHAT", "target", "Use APIKey", "required", 1, "progress", 0)
        );

        assertEquals("Use APIKey", restored.target());
        assertTrue(restored.matches("Use APIKey"));
        assertFalse(restored.matches("use apikey"));
    }
}
