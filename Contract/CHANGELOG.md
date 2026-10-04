# Changelog

## Unreleased

- **ALLIANCE terminal service (2026-10-01, CT-A01)**: persist unanimous UUID approvals, refund only funded signatures on cancellation/timeout, escalate active cancellation to dispute, and adjudicate a named defaulter through the admin-authorized service. Persist a source/recipient UUID principal plan, requester and READY/PAYING/PAID recipient states before Vault calls. Recovery resumes unstarted payments and skips confirmed credits; uncertain responses remain locked for reconciliation. Unresolved funding/payout records block new signing, alternate settlement, direct close and retention. Legacy journal records remain readable; do not downgrade with new plans. Lang v6→v7 retains operator edits. Player creation/GUI and real Paper/Folia/Vault balance acceptance remain pending; see [evidence](docs/alliance-settlement-evidence.md).

- **R1 payout uncertainty (2026-09-23)**: retain the write-ahead deposit record when Vault returns a failed payout response, dispute the WAGER, and reject replay with `REVIEW_REQUIRED` instead of risking a second credit. Automated tests cover a failed first payout and a failure reply after a partial credit; see [evidence](docs/r1-payout-uncertainty-2026-09-23.md).
- **Regions escrow replay (2026-09-23)**: acknowledge a persisted lock with the same operation ID and region even after the WAGER settles or becomes disputed. A different operation or region remains a conflict; a fresh eligibility check still rejects a terminal contract. See [R1 evidence](docs/r1-lock-replay-2026-09-23.md).

- **ALLIANCE substrate (not player-accessible)**: add three-or-more-member money-only model creation, immutable UUID-scoped funded signatures/approvals, `PENDING_ACCEPT_MULTI`, and deterministic principal-only refund/success/breach plans. This initial model slice did not execute payouts; the terminal service is described above. The optional `alliance` v1 save section rejects malformed signatures instead of dropping the contract or restoring a stale signature backup; do not downgrade saves containing ALLIANCE records. Lang v4→v5 adds multi-party state labels while retaining operator edits.
- **ALLIANCE funding service**: escrow creator/member stakes with UUID-specific operation IDs, activate only after the last signature, count partially funded memberships toward limits, and roll back failed signature saves. A phased pending journal separates prepared, withdrawn, refunding, refunded, and rejected outcomes; ambiguous Vault outcomes require manual review rather than automatic replay. Journal writes now use strict reads and atomic replacement; legacy entry fields remain readable. Do not downgrade while phased entries remain. Lang v5→v6 adds funding/recovery feedback without replacing custom text. This funding slice did not enable player creation or terminal execution; the terminal service is described above.

- **Optional integration**: mirror new completed, cancelled, expired, and disputed reputation deltas into Reputations when its Bukkit service is available.
- **Standalone contract**: retain Contract's local reputation store and behavior; an absent, disabled, reloaded, or incompatible Reputations provider cannot block local updates. Historical values are not imported automatically.
- **Packaging**: use the shaded, stateless `cubex-integrations` connector without compiling against or bundling the Reputations public API.
- **Escrow API**: publish `ContractEscrowService` for optional Regions funding with stable operation IDs, WAGER eligibility checks, lock conflicts, replayed terminal results, and explicit manual-review outcomes.
- **Settlement safety**: block ordinary settlement of a region-locked WAGER; Regions-controlled settlement uses existing pending-transaction recovery, while emergency admin refund uses the full WAGER timeout refund rules.
- **SALE flow**: let a seller preview and escrow the complete main-hand stack for a named buyer, escrow the buyer's Vault price on acceptance, require both sides to approve, and expose settlement/claim actions through commands, the wizard, details, and inbox. Changing the held item after preview fails closed; lang v3→v4 adds the bilingual flow without overwriting operator wording.

## 0.1.0 (2026-07-10)

- **Initial release**: add the Contract plugin as a first-class subproject in
  the plugins repository with Gradle resource version expansion.
- **Contract flows**: support service, wager, and partnership contracts with
  Vault-backed escrow, signing confirmations, disputes, mediation, admin
  settlement, cancellation, expiry, and retention cleanup.
- **GUI**: add the contract board, inbox, create wizard, details views, admin
  workbench, chat input fallback, and guarded Paper Dialog API support.
- **Storage**: persist contracts, pending transactions, event logs, reputation
  records, stored reward items, stored delivery items, and system objective
  progress in YAML-backed stores.
- **Objectives**: add system-verified service objectives for block, entity,
  player, chat, command, item, and money delivery progress.
- **Items**: support item rewards, item delivery, `/contract claim`, inventory
  rollback on failed claims, and RuleGems marker protection for delivered items.
- **Platform**: add Folia-aware scheduling through `cubex-scheduler`, declare
  `folia-supported`, and keep Adventure provided by Paper at runtime.
