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
    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 168;

    @Test
    void serviceDraftReadyWhenFieldsComplete() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Build a wall");
        draft.hours(24);
        draft.amount(500.0);
        assertNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
        assertTrue(draft.isReady(MIN, MAX, MIN_HOURS, MAX_HOURS));
    }

    @Test
    void rejectsMissingTitle() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.hours(24);
        draft.amount(500.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
    }

    @Test
    void rejectsAmountOutsideLimits() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Job");
        draft.hours(24);
        draft.amount(10.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
        draft.amount(999999.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
    }

    @Test
    void rejectsHoursOutsideLimits() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Job");
        draft.amount(500.0);
        draft.hours(0);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
        draft.hours(999);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
    }

    @Test
    void wagerRequiresCounterpartyAndArbiter() {
        CreateDraft draft = new CreateDraft(ContractType.WAGER);
        draft.title("Race bet");
        draft.hours(12);
        draft.amount(300.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS), "missing opponent should fail");
        draft.counterparty("Alex");
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS), "missing arbiter should fail");
        draft.mediator("Notch");
        assertNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
    }

    @Test
    void partnershipRequiresPartnerStake() {
        CreateDraft draft = new CreateDraft(ContractType.PARTNERSHIP);
        draft.title("Shop venture");
        draft.hours(48);
        draft.amount(1000.0);
        draft.counterparty("Alex");
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS), "missing partner stake should fail");
        draft.partnerStake(50.0);
        assertNotNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS), "partner stake below min should fail");
        draft.partnerStake(1000.0);
        assertNull(draft.validate(MIN, MAX, MIN_HOURS, MAX_HOURS));
    }

    @Test
    void serviceDoesNotRequireCounterparty() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        assertFalse(draft.needsCounterparty());
        assertFalse(draft.mediatorRequired());
    }
}
