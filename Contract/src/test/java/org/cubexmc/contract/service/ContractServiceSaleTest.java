package org.cubexmc.contract.service;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.economy.EconomyService;
import org.cubexmc.contract.model.Asset;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.storage.BatchAcceptanceStore;
import org.cubexmc.contract.storage.ContractStorage;
import org.cubexmc.contract.storage.EventLog;
import org.cubexmc.contract.storage.PendingTransactionStore;
import org.cubexmc.contract.storage.ReputationStore;
import org.cubexmc.core.CubexText;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractServiceSaleTest {
    private final ContractPlugin plugin = mock(ContractPlugin.class);
    private final ContractStorage storage = mock(ContractStorage.class);
    private final EconomyService economy = mock(EconomyService.class);
    private final PendingTransactionStore pending = mock(PendingTransactionStore.class);
    private final EventLog eventLog = mock(EventLog.class);
    private final BatchAcceptanceStore batchAcceptances = mock(BatchAcceptanceStore.class);
    private final ContractService service = new ContractService(plugin, storage, economy, pending, eventLog, batchAcceptances);

    private ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(4);
        when(item.clone()).thenReturn(item);
        when(item.isSimilar(item)).thenReturn(true);
        return item;
    }

    private Contract sale(UUID seller, UUID buyer) {
        return Contract.createSale(
            "sale-service",
            seller, "Alice",
            buyer, "Bob",
            List.of(Asset.item(item())),
            List.of(Asset.money(new BigDecimal("125.00"))),
            "Four diamonds", "description", 1_000L, Long.MAX_VALUE
        );
    }

    private Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    void serviceCreationEscrowsTheCompleteMainHandStack() throws IOException {
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Player seller = player(sellerId, "Alice");
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack offered = item();
        OfflinePlayer buyer = mock(OfflinePlayer.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("economy.min-reward", 100.0);
        config.set("economy.max-reward", 100000.0);
        config.set("limits.min-deadline-days", 1);
        config.set("limits.max-deadline-days", 7);
        config.set("limits.max-open-contracts", 3);
        when(seller.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(offered);
        when(buyer.getUniqueId()).thenReturn(buyerId);
        when(buyer.getName()).thenReturn("Bob");
        when(buyer.isOnline()).thenReturn(true);
        when(plugin.text()).thenReturn(new CubexText());
        when(plugin.getConfig()).thenReturn(config);
        when(storage.all()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedConstruction<ItemStack> airStacks = mockConstruction(ItemStack.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(buyer);

            ServiceResult result = service.createSale(
                seller, "Bob", new BigDecimal("125.00"), 3,
                "Four diamonds", "A direct item sale", null, offered
            );

            assertTrue(result.success());
            Contract sale = result.contract();
            assertEquals(ContractStatus.PENDING_ACCEPT, sale.status());
            assertEquals(4, sale.escrowedItems(ParticipantRole.PARTY_A).get(0).getAmount());
            assertEquals(new BigDecimal("125.00"),
                sale.participant(ParticipantRole.PARTY_B).orElseThrow().moneyStake());
            verify(inventory).setItemInMainHand(airStacks.constructed().get(0));
            verify(storage).put(sale);
            verify(storage).save();
        }
    }

    @Test
    void invitedBuyerAcceptsThroughSaleDispatch() throws IOException {
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Contract sale = sale(sellerId, buyerId);
        Player buyer = player(buyerId, "Bob");
        BigDecimal price = new BigDecimal("125.00");
        when(economy.has(buyer, price)).thenReturn(true);
        when(pending.beginWithdraw(buyerId, price, "sale-accept", sale.id())).thenReturn("withdraw-1");
        when(economy.withdraw(buyer, price)).thenReturn(EconomyService.TransactionResult.ok());

        ServiceResult result = service.accept(buyer, sale);

        assertTrue(result.success());
        assertEquals(ContractStatus.IN_PROGRESS, sale.status());
        assertEquals(buyerId, sale.contractorUuid());
        verify(storage).save();
        verify(pending).clear("withdraw-1");
    }

    @Test
    void secondApprovalSettlesMoneyAndCreatesBuyerItemClaim() throws IOException {
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Contract sale = sale(sellerId, buyerId);
        Player seller = player(sellerId, "Alice");
        Player buyer = player(buyerId, "Bob");
        BigDecimal price = new BigDecimal("125.00");
        ReputationStore reputation = mock(ReputationStore.class);
        when(plugin.reputation()).thenReturn(reputation);
        when(economy.has(buyer, price)).thenReturn(true);
        when(pending.beginWithdraw(buyerId, price, "sale-accept", sale.id())).thenReturn("withdraw-1");
        when(economy.withdraw(buyer, price)).thenReturn(EconomyService.TransactionResult.ok());
        when(pending.beginSettlement(eq(sale.id()), anyString())).thenReturn("settlement-1");
        when(pending.beginDeposit(
            eq(sellerId), eq(price), eq("SUCCESS"), eq(sale.id()), anyString(), eq("settlement-1")
        )).thenReturn("deposit-1");
        when(economy.deposit(sellerId, price)).thenReturn(EconomyService.TransactionResult.ok());
        assertTrue(service.accept(buyer, sale).success());

        assertTrue(service.approve(seller, sale).success());
        ServiceResult completed = service.approve(buyer, sale);

        assertTrue(completed.success());
        assertEquals(ContractStatus.COMPLETED, sale.status());
        assertEquals(price, completed.amount());
        assertEquals(4, sale.itemClaims(ParticipantRole.PARTY_B).get(0).getAmount());
        verify(economy).deposit(sellerId, price);
        verify(reputation).recordSettlement(sale, ContractStatus.COMPLETED);
    }
}
