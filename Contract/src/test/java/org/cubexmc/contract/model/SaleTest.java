package org.cubexmc.contract.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class SaleTest {
    private ItemStack stack(Material material, int amount) {
        ItemStack stack = Mockito.mock(ItemStack.class);
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("type", material.name());
        serialized.put("amount", amount);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        when(stack.serialize()).thenReturn(serialized);
        return stack;
    }

    private Contract sale() {
        return Contract.createSale(
            "sale-1",
            UUID.randomUUID(), "Alice",
            UUID.randomUUID(), "Bob",
            List.of(Asset.item(stack(Material.DIAMOND, 4))),
            List.of(Asset.money(new BigDecimal("125.00"))),
            "Four diamonds",
            "Bob buys four diamonds from Alice",
            1_000L,
            2_000L
        );
    }

    @Test
    void startsAsTwoPartyApprovalContract() {
        Contract sale = sale();

        assertEquals(ContractType.SALE, sale.type());
        assertEquals(ContractStatus.PENDING_ACCEPT, sale.status());
        assertEquals(ResolutionRule.BOTH_APPROVE, sale.resolutionRule());
        assertEquals(new BigDecimal("125.00"),
            sale.participant(ParticipantRole.PARTY_B).orElseThrow().moneyStake());
    }

    @Test
    void successSwapsMoneyAndPhysicalItems() {
        Contract sale = sale();
        List<PayoutRule> success = sale.payoutsFor(PayoutCondition.SUCCESS);

        PayoutRule moneySwap = success.stream()
            .filter(rule -> rule.source() == ParticipantRole.PARTY_B)
            .findFirst().orElseThrow();
        assertEquals(ParticipantRole.PARTY_A, moneySwap.recipient().role());
        assertEquals(new BigDecimal("125.00"), moneySwap.applyTo(new BigDecimal("125.00")));

        ItemClaimPlan plan = ItemClaimPlan.route(sale, success);
        sale.itemClaims(plan.claims());
        assertEquals(List.of(ParticipantRole.PARTY_A),
            sale.itemClaimSources(ParticipantRole.PARTY_B).stream().toList());
        assertEquals(Material.DIAMOND, sale.itemClaims(ParticipantRole.PARTY_B).get(0).getType());
        assertEquals(4, sale.itemClaims(ParticipantRole.PARTY_B).get(0).getAmount());
    }

    @Test
    void failureReturnsPhysicalItemsToTheirSource() {
        Contract sale = sale();
        ItemClaimPlan plan = ItemClaimPlan.route(sale, sale.payoutsFor(PayoutCondition.FAILURE));

        assertEquals(List.of(ParticipantRole.PARTY_A),
            plan.claims().get(ParticipantRole.PARTY_A).keySet().stream().toList());
        assertEquals(4,
            plan.claims().get(ParticipantRole.PARTY_A).get(ParticipantRole.PARTY_A).get(0).getAmount());
    }

    @Test
    void ambiguousPhysicalItemRecipientFailsClosed() {
        Contract sale = sale();
        List<PayoutRule> ambiguous = List.of(
            new PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.PARTY_A,
                PayoutRecipient.participant(ParticipantRole.PARTY_A), new BigDecimal("50")),
            new PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.PARTY_A,
                PayoutRecipient.participant(ParticipantRole.PARTY_B), new BigDecimal("50"))
        );

        assertThrows(IllegalArgumentException.class, () -> ItemClaimPlan.route(sale, ambiguous));
    }

    @Test
    void legacyServiceRewardPoolRemainsAClaimSource() {
        ItemStack emeralds = stack(Material.EMERALD, 7);
        Contract service = Contract.createService(
            "legacy-service",
            UUID.randomUUID(), "Alice",
            "Legacy reward", "description",
            BigDecimal.ZERO,
            List.of(emeralds),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1_000L,
            2_000L,
            null
        );
        service.participant(ParticipantRole.OWNER).orElseThrow()
            .stake(List.of(Asset.item("EMERALD x 7")));

        ItemClaimPlan plan = ItemClaimPlan.route(
            service,
            service.payoutsFor(PayoutCondition.SUCCESS)
        );

        assertEquals(7, plan.claims()
            .get(ParticipantRole.CONTRACTOR)
            .get(ParticipantRole.OWNER)
            .get(0)
            .getAmount());
    }
}
