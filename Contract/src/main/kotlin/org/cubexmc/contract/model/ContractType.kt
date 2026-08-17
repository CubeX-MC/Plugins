package org.cubexmc.contract.model

enum class ContractType {
    /** One-sided stake, open to any contractor. With an objective it settles automatically. */
    SERVICE,

    /** Both parties stake; an arbiter decides the winner. */
    WAGER,

    /** Both parties stake; both must approve. */
    PARTNERSHIP,

    // —— Not implemented yet; shown greyed out in the GUI. See PLAN.md §5.1. ——

    /** Needs multi-party acceptance and dynamically generated payouts. */
    ALLIANCE,

    /** PARTNERSHIP shape with swap payouts; blocked on ITEM assets. */
    SALE,

    /** Needs initial-transfer (money leaves escrow at creation) and a repayment step. */
    LOAN,

    // BOUNTY was removed: SERVICE + ResolutionRule.SYSTEM_OBJECTIVE with an ObjectiveType such as
    // KILL_PLAYER already provides "first claimer to do X is paid automatically". A separate type
    // would only have renamed OWNER/CONTRACTOR to POSTER/CLAIMER.
}
