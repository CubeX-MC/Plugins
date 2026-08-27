package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AllianceTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CARA = new UUID(0, 3);
    private static final UUID DAN = new UUID(0, 4);

    private Participant member(ParticipantRole role, UUID id, String amount) {
        return new Participant(role, id, id.toString(), List.of(Asset.money(new BigDecimal(amount))));
    }

    private Contract alliance() {
        return Contract.createAlliance("alliance", member(ParticipantRole.OWNER, OWNER, "10.00"),
            List.of(member(ParticipantRole.ALLY, BOB, "20.00"), member(ParticipantRole.ALLY, CARA, "30.00")),
            "Build together", "terms", 100L, 1_000L);
    }

    private void fundEveryone(Contract contract) {
        AllianceAgreement agreement = contract.allianceAgreement();
        for (UUID member : agreement.members()) agreement = agreement.accept(member, 200L);
        contract.allianceAgreement(agreement);
        contract.status(ContractStatus.IN_PROGRESS);
    }

    @Test
    void factoryStartsWithOnlyCreatorsSignatureAndNoRoleBasedPayouts() {
        Contract contract = alliance();
        assertEquals(ContractType.ALLIANCE, contract.type());
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        assertEquals(ResolutionRule.ALL_APPROVE, contract.resolutionRule());
        assertEquals(OWNER, contract.ownerUuid());
        assertNull(contract.contractorUuid());
        assertEquals(Map.of(OWNER, 100L), contract.allianceAgreement().signatures());
        assertFalse(contract.allianceAgreement().allAccepted());
        assertTrue(contract.payouts().isEmpty());
        assertTrue(contract.isExpired(1_000L));
    }

    @Test
    void acceptanceAndApprovalAreImmutableUuidScopedAndIdempotent() {
        AllianceAgreement original = alliance().allianceAgreement();
        AllianceAgreement partial = original.accept(BOB, 200L);
        assertFalse(original.hasAccepted(BOB));
        assertTrue(partial.hasAccepted(BOB));
        assertSame(partial, partial.accept(BOB, 300L));
        assertThrows(IllegalArgumentException.class, () -> partial.accept(DAN, 200L));
        assertThrows(IllegalArgumentException.class, () -> partial.accept(CARA, 99L));
        assertThrows(IllegalArgumentException.class, () -> partial.approve(BOB));
        AllianceAgreement complete = partial.accept(CARA, 250L);
        assertTrue(complete.allAccepted());
        assertFalse(complete.allApproved());
        AllianceAgreement approved = complete.approve(OWNER).approve(BOB).approve(CARA);
        assertTrue(approved.allApproved());
        assertSame(approved, approved.approve(BOB));
        assertThrows(IllegalArgumentException.class, () -> approved.approve(DAN));
        assertThrows(UnsupportedOperationException.class, () -> complete.signatures().put(DAN, 1L));
        assertThrows(UnsupportedOperationException.class, () -> complete.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> approved.approvals().clear());
    }

    @Test
    void pendingRefundIncludesOnlyFundedMembersDespiteRepeatedAllyRole() {
        Contract contract = alliance();
        contract.allianceAgreement(contract.allianceAgreement().accept(CARA, 200L));
        AlliancePayoutPlan plan = AlliancePayoutPlan.refund(contract);
        assertEquals(Map.of(OWNER, new BigDecimal("10.00"), CARA, new BigDecimal("30.00")), plan.payments());
        assertEquals(new BigDecimal("40.00"), plan.total());
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, contract.status());
        assertFalse(contract.allianceAgreement().hasAccepted(BOB));
    }

    @Test
    void successRequiresEverySignatureAndApprovalThenReturnsEachPrincipal() {
        Contract contract = alliance();
        assertThrows(IllegalArgumentException.class, () -> AlliancePayoutPlan.success(contract));
        fundEveryone(contract);
        assertThrows(IllegalArgumentException.class, () -> AlliancePayoutPlan.success(contract));
        contract.allianceAgreement(contract.allianceAgreement().approve(OWNER).approve(BOB).approve(CARA));
        AlliancePayoutPlan plan = AlliancePayoutPlan.success(contract);
        assertEquals(Map.of(OWNER, new BigDecimal("10.00"), BOB, new BigDecimal("20.00"),
            CARA, new BigDecimal("30.00")), plan.payments());
        assertEquals(new BigDecimal("60.00"), plan.total());
        assertEquals(ContractStatus.IN_PROGRESS, contract.status());
        contract.status(ContractStatus.COMPLETED);
        assertThrows(IllegalArgumentException.class, () -> AlliancePayoutPlan.success(contract));
        assertThrows(IllegalArgumentException.class, () -> AlliancePayoutPlan.refund(contract));
    }

    @Test
    void breachSplitsOnlyDefaultersPrincipalAndAllocatesCentsDeterministically() {
        // Intentionally not UUID-sorted: persistence/roster ordering must not move the remainder.
        Contract contract = Contract.createAlliance("four", member(ParticipantRole.OWNER, OWNER, "0.05"),
            List.of(member(ParticipantRole.ALLY, DAN, "3.00"), member(ParticipantRole.ALLY, CARA, "2.00"),
                member(ParticipantRole.ALLY, BOB, "1.00")), "title", "terms", 100L, 1_000L);
        fundEveryone(contract);
        assertThrows(IllegalArgumentException.class, () -> AlliancePayoutPlan.breach(contract, OWNER));
        contract.status(ContractStatus.DISPUTED);
        AlliancePayoutPlan plan = AlliancePayoutPlan.breach(contract, OWNER);
        assertEquals(Map.of(BOB, new BigDecimal("1.02"), CARA, new BigDecimal("2.02"),
            DAN, new BigDecimal("3.01")), plan.payments());
        assertEquals(new BigDecimal("6.05"), plan.total());
        assertEquals(3, plan.transfers().stream().filter(t -> t.sourceUuid().equals(OWNER)).count());
        assertThrows(IllegalArgumentException.class,
            () -> AlliancePayoutPlan.breach(contract, UUID.randomUUID()));
    }

    @Test
    void allyBreachIsNotMistakenForFirstAllyAndTinyPoolsDoNotCreateMoney() {
        Contract contract = alliance();
        fundEveryone(contract);
        contract.status(ContractStatus.DISPUTED);
        assertEquals(Map.of(OWNER, new BigDecimal("25.00"), BOB, new BigDecimal("35.00")),
            AlliancePayoutPlan.breach(contract, CARA).payments());

        Contract tiny = Contract.createAlliance("tiny", member(ParticipantRole.OWNER, OWNER, "0.01"),
            List.of(member(ParticipantRole.ALLY, DAN, "0.01"), member(ParticipantRole.ALLY, CARA, "0.01"),
                member(ParticipantRole.ALLY, BOB, "0.01")), "title", "terms", 100L, 1_000L);
        fundEveryone(tiny);
        tiny.status(ContractStatus.DISPUTED);
        AlliancePayoutPlan plan = AlliancePayoutPlan.breach(tiny, OWNER);
        assertEquals(new BigDecimal("0.04"), plan.total());
        assertEquals(new BigDecimal("0.02"), plan.payments().get(BOB));
        assertTrue(plan.transfers().stream().allMatch(t -> t.amount().signum() > 0));
    }

    @Test
    void invalidRosterOrNonCentMoneyCannotCreateAnAlliance() {
        Participant owner = member(ParticipantRole.OWNER, OWNER, "10");
        Participant bob = member(ParticipantRole.ALLY, BOB, "20");
        assertThrows(IllegalArgumentException.class, () -> Contract.createAlliance("bad", owner,
            List.of(bob), "title", "terms", 100L, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> Contract.createAlliance("bad", owner,
            List.of(bob, bob), "title", "terms", 100L, 1_000L));
        for (String invalid : List.of("-1", "0", "0.001")) {
            assertThrows(IllegalArgumentException.class, () -> Contract.createAlliance("bad", owner,
                List.of(bob, member(ParticipantRole.ALLY, CARA, invalid)), "title", "terms", 100L, 1_000L));
        }
        Participant items = new Participant(ParticipantRole.ALLY, CARA, "Cara", List.of(Asset.item("DIAMOND")));
        assertThrows(IllegalArgumentException.class, () -> Contract.createAlliance("bad", owner,
            List.of(bob, items), "title", "terms", 100L, 1_000L));
        Contract contract = Contract.createAlliance("copy", owner,
            List.of(bob, member(ParticipantRole.ALLY, CARA, "30")), "title", "terms", 100L, 1_000L);
        owner.stake(List.of());
        assertEquals(new BigDecimal("10"), contract.reward());
    }

    @Test
    void signatureSerializationRejectsMissingDuplicateForeignOrPrematureEntries() {
        AllianceAgreement original = alliance().allianceAgreement().accept(BOB, 200L);
        AllianceAgreement restored = AllianceAgreement.fromMap(original.members(), OWNER, original.toMap());
        assertEquals(original.signatures(), restored.signatures());
        assertEquals(Set.of(), restored.approvals());
        assertThrows(IllegalArgumentException.class, () -> AllianceAgreement.fromMap(original.members(), OWNER,
            Map.of("version", 1, "signatures", List.of(), "approvals", List.of())));
        assertThrows(IllegalArgumentException.class, () -> AllianceAgreement.fromMap(original.members(), OWNER,
            Map.of("version", 2, "signatures", List.of(), "approvals", List.of())));
        Map<String, Object> creator = Map.of("uuid", OWNER.toString(), "accepted-at", 100L);
        for (var invalid : List.of(
            List.of(creator, creator),
            List.of(creator, Map.of("uuid", DAN.toString(), "accepted-at", 200L)),
            List.of(creator, Map.of("uuid", BOB.toString(), "accepted-at", "1.5")))) {
            assertThrows(IllegalArgumentException.class, () -> AllianceAgreement.fromMap(original.members(), OWNER,
                Map.of("version", 1, "signatures", invalid, "approvals", List.of())));
        }
        assertThrows(IllegalArgumentException.class, () -> AllianceAgreement.fromMap(original.members(), OWNER,
            Map.of("version", 1, "signatures", List.of(creator), "approvals", List.of(OWNER.toString()))));
    }

    @Test
    void arbitraryMemberCountsConserveEverySourcesPrincipal() {
        java.util.Random random = new java.util.Random(20260827L);
        for (int size = 3; size <= 20; size++) {
            java.util.ArrayList<Participant> allies = new java.util.ArrayList<>();
            for (int i = 2; i <= size; i++) {
                allies.add(member(ParticipantRole.ALLY, new UUID(0, i),
                    BigDecimal.valueOf(random.nextInt(20_000) + 1, 2).toPlainString()));
            }
            Contract contract = Contract.createAlliance("sweep-" + size,
                member(ParticipantRole.OWNER, OWNER, "17.03"), allies, "title", "terms", 100L, 1_000L);
            fundEveryone(contract);
            contract.status(ContractStatus.DISPUTED);
            for (Participant defaulter : contract.participants()) {
                AlliancePayoutPlan plan = AlliancePayoutPlan.breach(contract, defaulter.uuid());
                assertFalse(plan.payments().containsKey(defaulter.uuid()));
                BigDecimal total = BigDecimal.ZERO;
                for (Participant source : contract.participants()) {
                    BigDecimal allocated = plan.transfers().stream().filter(t -> t.sourceUuid().equals(source.uuid()))
                        .map(AlliancePayoutPlan.Transfer::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertEquals(0, source.moneyStake().compareTo(allocated));
                    total = total.add(source.moneyStake());
                }
                assertEquals(0, total.compareTo(plan.total()));
            }
        }
    }

    @Test
    void signatureAttachmentRejectsWrongCreatorTimeExpiredSignatureAndChangedRoster() {
        Contract contract = alliance();
        assertThrows(IllegalArgumentException.class,
            () -> contract.allianceAgreement(contract.allianceAgreement().accept(BOB, 1_000L)));
        assertThrows(IllegalArgumentException.class, () -> contract.allianceAgreement(
            AllianceAgreement.create(contract.allianceAgreement().members(), OWNER, 101L)));
        contract.participantByUuid(BOB).orElseThrow().uuid(DAN);
        assertThrows(IllegalArgumentException.class, () -> AlliancePayoutPlan.refund(contract));
    }
}
