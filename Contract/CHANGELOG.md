# Changelog

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
