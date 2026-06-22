package org.cubexmc.contract.gui;

import org.cubexmc.contract.model.ContractType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateDraftTest {
    private static final double MIN = 100.0;
    private static final double MAX = 100000.0;
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 7;

    @Test
    void serviceDraftReadyWhenFieldsComplete() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Build a wall");
        draft.days(2);
        draft.amount(500.0);
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
        assertTrue(draft.isReady(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void rejectsMissingTitle() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.days(2);
        draft.amount(500.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void rejectsAmountOutsideLimits() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Job");
        draft.days(2);
        draft.amount(10.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
        draft.amount(999999.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void rejectsDaysOutsideLimits() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Job");
        draft.amount(500.0);
        draft.days(0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
        draft.days(999);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void wagerRequiresCounterpartyAndArbiter() {
        CreateDraft draft = new CreateDraft(ContractType.WAGER);
        draft.title("Race bet");
        draft.days(3);
        draft.amount(300.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS), "missing opponent should fail");
        draft.counterparty("Alex");
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS), "missing arbiter should fail");
        draft.mediator("Notch");
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void partnershipRequiresPartnerStake() {
        CreateDraft draft = new CreateDraft(ContractType.PARTNERSHIP);
        draft.title("Shop venture");
        draft.days(5);
        draft.amount(1000.0);
        draft.counterparty("Alex");
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS), "missing partner stake should fail");
        draft.partnerStake(50.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS), "partner stake below min should fail");
        draft.partnerStake(1000.0);
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void serviceDoesNotRequireCounterparty() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        assertFalse(draft.needsCounterparty());
        assertFalse(draft.mediatorRequired());
    }
}
