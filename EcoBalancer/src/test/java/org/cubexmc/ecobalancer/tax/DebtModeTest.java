package org.cubexmc.ecobalancer.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DebtModeTest {
    @Test
    void parsesDocumentedConfigValues() {
        assertEquals(DebtMode.SKIP, DebtMode.fromConfig("skip", DebtMode.DRAIN));
        assertEquals(DebtMode.DRAIN, DebtMode.fromConfig("drain", DebtMode.SKIP));
        assertEquals(DebtMode.ALLOW_NEGATIVE, DebtMode.fromConfig("allow-negative", DebtMode.SKIP));
        assertEquals(DebtMode.ALLOW_NEGATIVE, DebtMode.fromConfig("allow_negative", DebtMode.SKIP));
        assertEquals(DebtMode.INHERIT, DebtMode.fromConfig("inherit", DebtMode.SKIP));
    }

    @Test
    void fallsBackForBlankOrUnknownValues() {
        assertEquals(DebtMode.SKIP, DebtMode.fromConfig(null, DebtMode.SKIP));
        assertEquals(DebtMode.DRAIN, DebtMode.fromConfig(" ", DebtMode.DRAIN));
        assertEquals(DebtMode.SKIP, DebtMode.fromConfig("surprise", DebtMode.SKIP));
    }

    @Test
    void serializesToConfigValues() {
        assertEquals("skip", DebtMode.SKIP.toConfigValue());
        assertEquals("drain", DebtMode.DRAIN.toConfigValue());
        assertEquals("allow-negative", DebtMode.ALLOW_NEGATIVE.toConfigValue());
        assertEquals("inherit", DebtMode.INHERIT.toConfigValue());
    }
}
