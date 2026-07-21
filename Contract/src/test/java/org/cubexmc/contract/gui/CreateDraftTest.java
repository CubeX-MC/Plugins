package org.cubexmc.contract.gui;

import org.cubexmc.contract.model.BatchRepeatPolicy;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.ObjectiveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void serviceBatchCountRespectsConfiguredLimit() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Build roads");
        draft.days(2);
        draft.amount(500.0);

        assertEquals(1, draft.contractCount());
        draft.contractCount(64);
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS, 64));
        draft.contractCount(65);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS, 64));
        draft.contractCount(0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS, 64));
    }

    @Test
    void cooldownBatchRespectsConfiguredHourLimit() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Repeatable patrol");
        draft.days(2);
        draft.amount(500.0);
        draft.contractCount(10);
        draft.repeatPolicy(BatchRepeatPolicy.COOLDOWN);

        assertEquals(BatchRepeatPolicy.ONCE, new CreateDraft(ContractType.SERVICE).repeatPolicy());
        draft.repeatCooldownHours(0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS, 64, 720));
        draft.repeatCooldownHours(24);
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS, 64, 720));
        draft.repeatCooldownHours(721);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS, 64, 720));
    }

    @Test
    void serviceDraftAllowsZeroAmountWhenMinimumIsZero() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Selling shulker boxes");
        draft.days(2);
        draft.amount(0.0);
        assertNull(draft.validate(0.0, MAX, MIN_DAYS, MAX_DAYS));
        assertTrue(draft.isReady(0.0, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void serviceItemRewardDoesNotRequireMoneyAmount() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Trade diamonds");
        draft.days(2);
        draft.itemReward(true);

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

    @Test
    void systemVerifiedServiceRequiresObjectiveDetails() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Kill zombies");
        draft.days(2);
        draft.amount(500.0);
        draft.objectiveType(ObjectiveType.KILL_ENTITY);

        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));

        draft.objectiveTarget("ZOMBIE");
        draft.objectiveRequired(0);
        assertNotNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));

        draft.objectiveRequired(10);
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
        assertTrue(draft.systemVerified());
    }

    @Test
    void systemVerifiedServiceCanUseAnyChatTarget() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Say hello");
        draft.days(2);
        draft.amount(500.0);
        draft.objectiveType(ObjectiveType.CHAT);
        draft.objectiveTarget(null);
        draft.objectiveRequired(1);

        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }

    @Test
    void systemVerifiedServiceCanDeliverMoney() {
        CreateDraft draft = new CreateDraft(ContractType.SERVICE);
        draft.title("Pay for item");
        draft.days(2);
        draft.itemReward(true);
        draft.objectiveType(ObjectiveType.DELIVER_MONEY);
        draft.objectiveRequired(250);

        assertEquals("MONEY", draft.objectiveTarget());
        assertNull(draft.validate(MIN, MAX, MIN_DAYS, MAX_DAYS));
    }
}
