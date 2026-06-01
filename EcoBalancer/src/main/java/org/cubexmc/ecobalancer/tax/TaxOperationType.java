package org.cubexmc.ecobalancer.tax;

public enum TaxOperationType {
    CHECK_ALL("checkall"),
    CHECK_PLAYER("checkplayer"),
    POLICY_EXECUTE("policy"),
    SCHEDULED("scheduled"),
    GUI("gui");

    private final String configKey;

    TaxOperationType(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
