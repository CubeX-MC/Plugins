package org.cubexmc.contract.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReputationStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void withdrawingDisputeRollsBackCountWithoutGoingNegative() {
        ReputationStore store = new ReputationStore(
            tempDir.resolve("reputation.yml").toFile(),
            Logger.getLogger("ReputationStoreTest")
        );
        UUID player = UUID.randomUUID();

        store.recordDisputed(player, "Alex");
        store.recordDisputed(player, "Alex");
        store.recordDisputeWithdrawn(player, "Alex");
        assertEquals(1, store.snapshot(player).getDisputed());

        store.recordDisputeWithdrawn(player, "Alex");
        store.recordDisputeWithdrawn(player, "Alex");
        assertEquals(0, store.snapshot(player).getDisputed());
    }
}
