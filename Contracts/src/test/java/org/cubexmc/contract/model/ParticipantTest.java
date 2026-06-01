package org.cubexmc.contract.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticipantTest {
    @Test
    void moneyStakeSumsAllMoneyAssets() {
        Participant p = new Participant(
            ParticipantRole.OWNER,
            UUID.randomUUID(),
            "Alice",
            List.of(
                Asset.money(new BigDecimal("50")),
                Asset.money(new BigDecimal("25.50")),
                Asset.item("DIAMOND")
            )
        );
        assertEquals(new BigDecimal("75.50"), p.moneyStake());
    }

    @Test
    void roundTripWithMoneyStake() {
        UUID uuid = UUID.randomUUID();
        Participant original = new Participant(
            ParticipantRole.OWNER,
            uuid,
            "Bob",
            List.of(Asset.money(new BigDecimal("100")))
        );
        Participant restored = Participant.fromMap(original.toMap());
        assertEquals(ParticipantRole.OWNER, restored.role());
        assertEquals(uuid, restored.uuid());
        assertEquals("Bob", restored.displayName());
        assertEquals(new BigDecimal("100"), restored.moneyStake());
    }

    @Test
    void participantWithoutUuid() {
        Participant p = new Participant(ParticipantRole.CONTRACTOR, null, null, List.of());
        Participant restored = Participant.fromMap(p.toMap());
        assertEquals(ParticipantRole.CONTRACTOR, restored.role());
        assertEquals(null, restored.uuid());
    }
}
