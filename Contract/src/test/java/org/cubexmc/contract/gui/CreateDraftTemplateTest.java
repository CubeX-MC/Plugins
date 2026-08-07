package org.cubexmc.contract.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.cubexmc.contract.model.BatchRepeatPolicy;
import org.cubexmc.contract.model.ContractSpec;
import org.cubexmc.contract.model.ContractType;
import org.junit.jupiter.api.Test;

class CreateDraftTemplateTest {
    @Test
    void templateRoundTripCopiesTermsButNotScheduleState() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Buy stone");
        draft.description("Deliver a stack");
        draft.days(3);
        draft.amount(500D);
        draft.contractCount(8);
        draft.repeatPolicy(BatchRepeatPolicy.COOLDOWN);
        draft.repeatCooldownHours(12);
        draft.publishAt(9_999L);

        ContractSpec spec = draft.toSpec();
        CreateDraft loaded = CreateDraft.fromSpec(spec);

        assertEquals("Buy stone", loaded.title());
        assertEquals(8, loaded.contractCount());
        assertEquals(BatchRepeatPolicy.COOLDOWN, loaded.repeatPolicy());
        assertEquals(12, loaded.repeatCooldownHours());
        assertNull(loaded.publishAt(), "templates must not copy live scheduling state");
    }
}

