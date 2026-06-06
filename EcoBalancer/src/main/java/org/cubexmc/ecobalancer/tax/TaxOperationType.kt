package org.cubexmc.ecobalancer.tax

enum class TaxOperationType(val configKey: String) {
    CHECK_ALL("checkall"),
    CHECK_PLAYER("checkplayer"),
    POLICY_EXECUTE("policy"),
    SCHEDULED("scheduled"),
    GUI("gui"),
}
