package org.cubexmc.contract.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.cubexmc.core.CubexLogger;
import org.cubexmc.contract.model.BatchRepeatPolicy;
import org.cubexmc.contract.model.ContractSpec;
import org.cubexmc.contract.model.ContractTemplate;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.TemplateVisibility;
import org.cubexmc.contract.storage.ContractTemplateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractTemplateServiceTest {
    @TempDir Path tempDir;

    @Test
    void foreignPrivateTemplatesStayHiddenAndProtected() {
        UUID owner = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        var store = new ContractTemplateStore(tempDir.resolve("templates.yml").toFile(), new CubexLogger(Logger.getLogger("templates")));
        var service = new ContractTemplateService(store, 4);
        ContractTemplate saved = service.save(owner, "Owner", "Purchase", spec()).getTemplate();

        assertTrue(service.visibleTo(owner, false).contains(saved));
        assertFalse(service.visibleTo(viewer, true).contains(saved), "template admins must not browse private templates");
        assertFalse(service.delete(viewer, saved.getId(), false).getSuccess());
        assertFalse(service.toggleVisibility(owner, saved.getId(), false).getSuccess());

        ContractTemplate server = service.toggleVisibility(owner, saved.getId(), true).getTemplate();
        assertEquals(TemplateVisibility.SERVER, server.getVisibility());
        assertTrue(service.visibleTo(viewer, false).contains(server));
    }

    @Test
    void enforcesPerOwnerLimit() {
        UUID owner = UUID.randomUUID();
        var store = new ContractTemplateStore(tempDir.resolve("limited.yml").toFile(), new CubexLogger(Logger.getLogger("templates")));
        var service = new ContractTemplateService(store, 1);

        assertTrue(service.save(owner, "Owner", "First", spec()).getSuccess());
        assertFalse(service.save(owner, "Owner", "Second", spec()).getSuccess());
    }

    private ContractSpec spec() {
        return new ContractSpec(ContractType.SERVICE, "Purchase", "Buy logs", 2, 400D,
                false, null, null, null, null, null, null, 4, BatchRepeatPolicy.ONCE, 24);
    }
}
