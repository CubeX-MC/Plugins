package org.cubexmc.contract.model

enum class ParticipantRole {
    OWNER,
    CONTRACTOR,
    PARTNER,
    ALLY,
    PARTY_A,
    PARTY_B,
    // POSTER/CLAIMER were removed with BOUNTY; SERVICE's OWNER/CONTRACTOR cover that shape.
    DEBTOR,
    CREDITOR,
    MEDIATOR,
}
