# Changelog

## Unreleased

- **Optional integration**: mirror new completed, cancelled, expired, and disputed reputation deltas into Reputations when its Bukkit service is available.
- **Standalone contract**: retain Contract's local reputation store and behavior; an absent, disabled, reloaded, or incompatible Reputations provider cannot block local updates. Historical values are not imported automatically.
- **Packaging**: use the shaded, stateless `cubex-integrations` connector without compiling against or bundling the Reputations public API.
- **Escrow API**: publish `ContractEscrowService` for optional Regions funding with stable operation IDs, WAGER eligibility checks, lock conflicts, replayed terminal results, and explicit manual-review outcomes.
- **Settlement safety**: block ordinary settlement of a region-locked WAGER; Regions-controlled settlement uses existing pending-transaction recovery, while emergency admin refund uses the full WAGER timeout refund rules.

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
