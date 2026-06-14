package org.cubexmc.reputations.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReputationStoreTest {

    private static final Logger LOG = Logger.getLogger("ReputationStoreTest");

    @Test
    void persistsValuesAndNameAcrossLoad(@TempDir Path dir) {
        File file = new File(dir.toFile(), "reputation-data.yml");
        UUID id = UUID.randomUUID();

        ReputationStore store = new ReputationStore(file, LOG);
        store.load();
        store.set(id, "contract:completed", 3.0);
        store.add(id, "contract:completed", 2.0, 0.0);
        store.cacheName(id, "Steve");
        store.save();

        ReputationStore reloaded = new ReputationStore(file, LOG);
        reloaded.load();
        assertEquals(5.0, reloaded.get(id, "contract:completed", 0.0));
        assertEquals(id, reloaded.findByName("steve"));
    }

    @Test
    void getReturnsFallbackWhenUnset(@TempDir Path dir) {
        ReputationStore store = new ReputationStore(new File(dir.toFile(), "rep.yml"), LOG);
        store.load();
        assertEquals(7.0, store.get(UUID.randomUUID(), "x:y", 7.0));
    }

    @Test
    void resetRemovesStoredValue(@TempDir Path dir) {
        ReputationStore store = new ReputationStore(new File(dir.toFile(), "rep.yml"), LOG);
        store.load();
        UUID id = UUID.randomUUID();
        store.set(id, "a:b", 4.0);
        store.reset(id, "a:b");
        assertEquals(0.0, store.get(id, "a:b", 0.0));
        assertNull(store.findByName("nobody"));
    }
}
