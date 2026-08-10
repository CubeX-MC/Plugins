package org.cubexmc.contract.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    /**
     * 随包发布的预设必须真的能解析出来。read() 遇到不认识的枚举名会抛异常，load() 又把它咽成一行
     * warning——枚举名写错的话模板库只是静默地空着，控制台一行 Skipping 谁也不会注意到。
     */
    @Test
    void shippedPresetsParse() throws Exception {
        var file = tempDir.resolve("templates.yml").toFile();
        try (var stream = getClass().getResourceAsStream("/templates.yml");
                var out = new java.io.FileOutputStream(file)) {
            assertNotNull(stream, "missing shipped templates.yml");
            stream.transferTo(out);
        }
        var store = new ContractTemplateStore(file, new CubexLogger(Logger.getLogger("templates")));
        store.load();

        var presets = store.all();
        assertFalse(presets.isEmpty(), "shipped templates.yml parsed to nothing");
        for (var preset : presets) {
            // 全服可见、且不属于任何真实玩家——否则预设会占掉某个人的模板名额，别人也看不到。
            assertEquals(TemplateVisibility.SERVER, preset.getVisibility(), preset.getId() + " must be server-wide");
            assertEquals(SERVER_OWNER, preset.getOwnerUuid(), preset.getId() + " must not belong to a player");
            assertNotNull(preset.getSpec().getType(), preset.getId() + " has no contract type");
        }
    }

    private static final UUID SERVER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000000");
}

