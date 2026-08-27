package org.cubexmc.contract.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.model.*;
import org.cubexmc.core.CubexLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AllianceStorageTest {
    @TempDir Path directory;
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final UUID CARA = new UUID(0, 3);

    private ContractStorage storage() {
        return new ContractStorage(directory.resolve("contract.yml").toFile(), new CubexLogger(Logger.getAnonymousLogger()));
    }

    private Participant member(ParticipantRole role, UUID id, String name, String amount) {
        return new Participant(role, id, name, List.of(Asset.money(new BigDecimal(amount))));
    }

    private Contract alliance() {
        return Contract.createAlliance("alliance", member(ParticipantRole.OWNER, OWNER, "Owner", "10.01"),
            List.of(member(ParticipantRole.ALLY, BOB, "Bob", "20.00"),
                member(ParticipantRole.ALLY, CARA, "Cara", "30.00")), "title", "terms", 100L, 1_000L);
    }

    @Test
    void partialAcceptanceRoundTripsWithoutFundingAnUnsignedAlly() throws Exception {
        Contract contract = alliance();
        contract.allianceAgreement(contract.allianceAgreement().accept(CARA, 200L));
        ContractStorage original = storage();
        original.put(contract);
        original.save();
        ContractStorage reloaded = storage();
        reloaded.load();
        Contract restored = reloaded.findById("alliance").orElseThrow();
        assertEquals(ContractStatus.PENDING_ACCEPT_MULTI, restored.status());
        assertEquals(ResolutionRule.ALL_APPROVE, restored.resolutionRule());
        assertEquals(Map.of(OWNER, 100L, CARA, 200L), restored.allianceAgreement().signatures());
        assertEquals(new BigDecimal("40.01"), AlliancePayoutPlan.refund(restored).total());
        reloaded.save();
        reloaded.load();
        assertFalse(reloaded.findById("alliance").orElseThrow().allianceAgreement().hasAccepted(BOB));
    }

    @Test
    void approvalsAndBreachAllocationSurviveReload() throws Exception {
        Contract contract = alliance();
        contract.allianceAgreement(contract.allianceAgreement().accept(BOB, 200L).accept(CARA, 300L)
            .approve(OWNER).approve(BOB).approve(CARA));
        contract.status(ContractStatus.IN_PROGRESS);
        contract.acceptedAt(300L);
        ContractStorage original = storage();
        original.put(contract);
        original.save();
        ContractStorage reloaded = storage();
        reloaded.load();
        Contract restored = reloaded.findById("alliance").orElseThrow();
        assertTrue(restored.allianceAgreement().allApproved());
        assertEquals(300L, restored.acceptedAt());
        assertEquals(AlliancePayoutPlan.success(contract).payments(), AlliancePayoutPlan.success(restored).payments());
        contract.status(ContractStatus.DISPUTED);
        restored.status(ContractStatus.DISPUTED);
        assertEquals(AlliancePayoutPlan.breach(contract, OWNER).payments(), AlliancePayoutPlan.breach(restored, OWNER).payments());
    }

    @Test
    void existingServiceStillRoundTripsWithoutAnAllianceSection() throws Exception {
        Contract contract = Contract.createService("service", OWNER, "Owner", "title", "terms",
            new BigDecimal("25.00"), BigDecimal.ZERO, BigDecimal.ZERO, 100L, 1_000L);
        ContractStorage original = storage();
        original.put(contract);
        original.put(alliance());
        original.save();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(directory.resolve("contract.yml").toFile());
        assertFalse(yaml.contains("contracts.service.alliance"));
        ContractStorage reloaded = storage();
        reloaded.load();
        assertNull(reloaded.findById("service").orElseThrow().allianceAgreement());
        assertEquals(new BigDecimal("25.00"), reloaded.findById("service").orElseThrow().reward());
        assertEquals(2, reloaded.all().size());
    }

    @Test
    void missingSignaturesRefuseLoadInsteadOfDroppingContractOrUsingStaleBackup() throws Exception {
        ContractStorage original = storage();
        Contract contract = alliance();
        original.put(contract);
        original.save();
        contract.allianceAgreement(contract.allianceAgreement().accept(BOB, 200L));
        original.save(); // Valid but older backup lacks Bob's funded signature.
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(directory.resolve("contract.yml").toFile());
        yaml.set("contracts.alliance.alliance", null);
        yaml.save(directory.resolve("contract.yml").toFile());
        assertThrows(IllegalStateException.class, original::load);
        assertTrue(original.findById("alliance").orElseThrow().allianceAgreement().hasAccepted(BOB));
        assertThrows(IllegalStateException.class, () -> storage().load());
    }

    @Test
    void foreignOrFractionalSignatureCannotBeRestoredAsValidEscrow() throws Exception {
        ContractStorage original = storage();
        original.put(alliance());
        original.save();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(directory.resolve("contract.yml").toFile());
        yaml.set("contracts.alliance.alliance.signatures", List.of(
            Map.of("uuid", OWNER.toString(), "accepted-at", 100L),
            Map.of("uuid", UUID.randomUUID().toString(), "accepted-at", 200L)));
        yaml.save(directory.resolve("contract.yml").toFile());
        assertThrows(IllegalStateException.class, () -> storage().load());
        yaml.set("contracts.alliance.alliance.signatures", List.of(
            Map.of("uuid", OWNER.toString(), "accepted-at", 100.5)));
        yaml.save(directory.resolve("contract.yml").toFile());
        assertThrows(IllegalStateException.class, () -> storage().load());
    }
}
