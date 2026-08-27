package org.cubexmc.contract.model

enum class ContractType {
    /** One-sided stake, open to any contractor. With an objective it settles automatically. */
    SERVICE,

    /** Both parties stake; an arbiter decides the winner. */
    WAGER,

    /** Both parties stake; both must approve. */
    PARTNERSHIP,

    // —— Types below are tracked individually in PLAN.md §5.1. ——

    /** Model and phased funding exist; terminal settlement and player creation remain pending. */
    ALLIANCE,

    /** Item-for-money swap with command/GUI creation and role-owned settlement. */
    SALE,

    /** Needs initial-transfer (money leaves escrow at creation) and a repayment step. */
    LOAN,

    // BOUNTY was removed: SERVICE + ResolutionRule.SYSTEM_OBJECTIVE with an ObjectiveType such as
    // KILL_PLAYER already provides "first claimer to do X is paid automatically". A separate type
    // would only have renamed OWNER/CONTRACTOR to POSTER/CLAIMER.
}
