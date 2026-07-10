package org.cubexmc.contract.storage;

import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractObjective;
import org.cubexmc.contract.model.ObjectiveType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers crash-safe persistence of the contract database (C1): atomic save, rolling backup, recovery. */
class ContractStorageTest {
    @TempDir
    Path tempDir;

    private ContractStorage newStorage() {
        return new ContractStorage(tempDir.resolve("contract.yml").toFile(), Logger.getAnonymousLogger());
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

}
