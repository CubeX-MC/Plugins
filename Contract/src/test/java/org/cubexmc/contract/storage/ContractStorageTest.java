package org.cubexmc.contract.storage;

import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractObjective;
import org.cubexmc.contract.model.Asset;
import org.cubexmc.contract.model.ItemClaimPlan;
import org.cubexmc.contract.model.ObjectiveType;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.model.PayoutCondition;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cubexmc.core.CubexLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Covers crash-safe persistence of the contract database (C1): atomic save, rolling backup, recovery. */
class ContractStorageTest {
    @TempDir
    Path tempDir;

    private ContractStorage newStorage() {
        return new ContractStorage(tempDir.resolve("contract.yml").toFile(), new CubexLogger(Logger.getAnonymousLogger()));
    }

    private Contract sampleContract() {
        return Contract.createService(UUID.randomUUID().toString(), UUID.randomUUID(), "Owner",
            "Build a wall", "description", new BigDecimal("500.00"), new BigDecimal("20.00"),
            new BigDecimal("5"), 1000L, 2000L);
    }

    @Test
    void savesAndReloadsRoundTrip() throws IOException {
        ContractStorage storage = newStorage();
        Contract contract = sampleContract();
        storage.put(contract);
        storage.save();

        ContractStorage reloaded = newStorage();
        reloaded.load();

        assertEquals(1, reloaded.all().size());
        assertEquals(contract.id(), reloaded.all().get(0).id());
        assertEquals(new BigDecimal("500.00"), reloaded.all().get(0).reward());
    }

    @Test
    void secondSaveRollsPreviousFileIntoBackup() throws IOException {
        ContractStorage storage = newStorage();
        storage.put(sampleContract());
        storage.save();             // creates contract.yml (no prior file → no backup yet)
        storage.put(sampleContract());
        storage.save();             // previous contract.yml rolled into contract.yml.bak

        assertTrue(tempDir.resolve("contract.yml").toFile().exists());
        assertTrue(tempDir.resolve("contract.yml.bak").toFile().exists());
    }

    @Test
    void recoversFromBackupWhenMainFileCorrupt() throws IOException {
        ContractStorage storage = newStorage();
        Contract first = sampleContract();
        storage.put(first);
        storage.save();             // contract.yml = {first}
        storage.put(sampleContract());
        storage.save();             // contract.yml = {first, second}; contract.yml.bak = {first}

        Files.writeString(tempDir.resolve("contract.yml"), "contracts: {unterminated", StandardCharsets.UTF_8);

        ContractStorage reloaded = newStorage();
        reloaded.load();            // main is corrupt → must fall back to the backup

        assertEquals(1, reloaded.all().size());
        assertEquals(first.id(), reloaded.all().get(0).id());
    }

    @Test
    void refusesToStartWhenMainAndBackupBothCorrupt() throws IOException {
        Files.writeString(tempDir.resolve("contract.yml"), "contracts: {unterminated", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("contract.yml.bak"), "also: {broken", StandardCharsets.UTF_8);

        ContractStorage storage = newStorage();
        // Refuse rather than silently start empty and orphan escrowed funds.
        assertThrows(IllegalStateException.class, storage::load);
    }

    @Test
    void firstRunWithNoFileLoadsEmpty() {
        ContractStorage storage = newStorage();
        storage.load();
        assertTrue(storage.all().isEmpty());
    }

    @Test
    void savesAndReloadsServiceObjective() throws IOException {
        ContractStorage storage = newStorage();
        Contract contract = Contract.createService(UUID.randomUUID().toString(), UUID.randomUUID(), "Owner",
            "Kill zombies", "description", new BigDecimal("500.00"), new BigDecimal("20.00"),
            new BigDecimal("5"), 1000L, 2000L, ContractObjective.of(ObjectiveType.KILL_ENTITY, "zombie", 10));
        contract.objective().addProgress(4);
        storage.put(contract);
        storage.save();

        ContractStorage reloaded = newStorage();
        reloaded.load();

        Contract loaded = reloaded.all().get(0);
        assertEquals(ObjectiveType.KILL_ENTITY, loaded.objective().type());
        assertEquals("ZOMBIE", loaded.objective().target());
        assertEquals(10, loaded.objective().required());
        assertEquals(4, loaded.objective().progress());
    }

    private ItemStack stack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("type", material.name());
        serialized.put("amount", amount);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        when(stack.serialize()).thenReturn(serialized);
        return stack;
    }

    @Test
    void savesAndReloadsRoleOwnedItemClaims() throws IOException {
        ItemStack emeralds = stack(Material.EMERALD, 7);
        Contract contract = Contract.createSale(
            "sale-storage",
            UUID.randomUUID(), "Alice",
            UUID.randomUUID(), "Bob",
            java.util.List.of(Asset.item(emeralds)),
            java.util.List.of(Asset.money(new BigDecimal("40.00"))),
            "Emerald sale", "description", 1_000L, 2_000L
        );
        contract.itemClaims(ItemClaimPlan.route(
            contract,
            contract.payoutsFor(PayoutCondition.SUCCESS)
        ).claims());
        ContractStorage storage = newStorage();
        storage.put(contract);
        storage.save();

        Contract loaded;
        try (MockedStatic<ItemStack> statics = mockStatic(ItemStack.class)) {
            statics.when(() -> ItemStack.deserialize(anyMap())).thenReturn(emeralds);
            ContractStorage reloaded = newStorage();
            reloaded.load();
            loaded = reloaded.findById("sale-storage").orElseThrow();
        }

        assertEquals(java.util.Set.of(ParticipantRole.PARTY_A),
            loaded.itemClaimSources(ParticipantRole.PARTY_B));
        assertEquals(Material.EMERALD, loaded.itemClaims(ParticipantRole.PARTY_B).get(0).getType());
        assertEquals(7, loaded.itemClaims(ParticipantRole.PARTY_B).get(0).getAmount());
    }

}
