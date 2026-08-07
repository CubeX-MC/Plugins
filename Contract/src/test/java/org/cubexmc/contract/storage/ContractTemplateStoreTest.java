package org.cubexmc.contract.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.cubexmc.core.CubexLogger;
import org.cubexmc.contract.model.BatchRepeatPolicy;
import org.cubexmc.contract.model.ContractSpec;
import org.cubexmc.contract.model.ContractTemplate;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.TemplateVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractTemplateStoreTest {
    @TempDir Path tempDir;

    @Test
    void savesAndLoadsTermsWithoutRuntimeState() throws Exception {
        var file = tempDir.resolve("templates.yml").toFile();
        var store = new ContractTemplateStore(file, new CubexLogger(Logger.getLogger("templates")));
        var spec = new ContractSpec(ContractType.SERVICE, "Purchase", "Buy logs", 2, 400D,
                false, null, null, null, null, null, null, 16, BatchRepeatPolicy.ONCE, 24);
        var template = new ContractTemplate("template-1", UUID.randomUUID(), "Owner", "Log purchase",
                TemplateVisibility.SERVER, spec, 100L, 200L);
        store.put(template);
        store.flushIfDirty();

        var reloaded = new ContractTemplateStore(file, new CubexLogger(Logger.getLogger("templates")));
        reloaded.load();

        assertEquals(1, reloaded.all().size());
        assertEquals("Purchase", reloaded.find("template-1").getSpec().getTitle());
        assertEquals(16, reloaded.find("template-1").getSpec().getContractCount());
        assertEquals(TemplateVisibility.SERVER, reloaded.find("template-1").getVisibility());
    }
}

