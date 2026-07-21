package org.cubexmc.contract.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchAcceptanceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptanceHistorySurvivesReload() throws Exception {
        File file = tempDir.resolve("batch-acceptance.yml").toFile();
        UUID player = UUID.randomUUID();
        BatchAcceptanceStore store = new BatchAcceptanceStore(file, Logger.getLogger("BatchAcceptanceStoreTest"));

        store.record("batch-a", player, 12_345L, "contract-a");
        store.record("batch-a", player, 10_000L, "older-contract");
        assertTrue(store.isDirty());
        store.save();
        assertFalse(store.isDirty());

        BatchAcceptanceStore reloaded = new BatchAcceptanceStore(file, Logger.getLogger("BatchAcceptanceStoreTest"));
        reloaded.load();
        assertEquals(12_345L, reloaded.lastAcceptedAt("batch-a", player));
    }

    @Test
    void cleanupDropsHistoryForPurgedBatches() throws Exception {
        File file = tempDir.resolve("batch-acceptance.yml").toFile();
        UUID player = UUID.randomUUID();
        BatchAcceptanceStore store = new BatchAcceptanceStore(file, Logger.getLogger("BatchAcceptanceStoreTest"));
        store.record("keep", player, 100L, "contract-keep");
        store.record("purge", player, 200L, "contract-purge");

        store.retainBatches(Set.of("keep"));
        store.save();

        BatchAcceptanceStore reloaded = new BatchAcceptanceStore(file, Logger.getLogger("BatchAcceptanceStoreTest"));
        reloaded.load();
        assertEquals(100L, reloaded.lastAcceptedAt("keep", player));
        assertNull(reloaded.lastAcceptedAt("purge", player));
    }
}
