package org.cubexmc.contract.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.cubexmc.core.CubexLogger;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduledContractStorageTest {
    @TempDir Path tempDir;

    @Test
    void scheduledStatusAndTypedPublishAtSurviveRestart() throws Exception {
        long publishAt = 50_000L;
        Contract contract = Contract.createScheduledService(
                "scheduled-1", UUID.randomUUID(), "Owner", "Future job", "Description",
                BigDecimal.TEN, List.of(), BigDecimal.ONE, BigDecimal.ZERO,
                1_000L, 100_000L, null, publishAt);
        var file = tempDir.resolve("contract.yml").toFile();
        var store = new ContractStorage(file, new CubexLogger(Logger.getLogger("scheduled")));
        store.put(contract);
        store.save();

        var reloaded = new ContractStorage(file, new CubexLogger(Logger.getLogger("scheduled")));
        reloaded.load();
        Contract restored = reloaded.findById("scheduled-1").orElseThrow();

        assertEquals(ContractStatus.SCHEDULED, restored.status());
        assertEquals(publishAt, restored.publishAt());
        assertEquals(100_000L, restored.expiresAt());
    }
}
