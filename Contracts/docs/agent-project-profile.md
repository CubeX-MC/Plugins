# Agent Project Profile

This file contains Contract-specific facts for agents. Reusable skills should
read this file instead of embedding project-specific knowledge.

## Project Identity

- Project: Contract
- Type: Minecraft server plugin
- Language/runtime: Java 17, Maven, Spigot API compile baseline
- Main class: `org.cubexmc.contract.ContractPlugin`
- Artifact: `target/contract-<version>.jar`
- Core promise: provide a player-to-player contract board with Vault-backed
  escrow, submissions, approval, cancellation, disputes, and admin settlement.

## Runtime Matrix

- Compile API: Spigot API 1.18.2
- Plugin API version: `1.18`
- Java build target: 17
- Required plugin dependency: Vault
- Runtime economy dependency: any Vault-compatible economy provider.
- Server platforms claimed in docs: Bukkit/Spigot-compatible servers; Paper is
  expected but not separately declared.
- Folia declaration: none in `plugin.yml`; do not strengthen Folia support
  claims without evidence.
- Build packaging: Maven shade plugin packages and relocates AnvilGUI under
  `org.cubexmc.contract.lib.anvilgui`; keep the AnvilGUI version aligned with
  the target Minecraft/Paper runtime before release builds.

Platform and dependency claims must stay synchronized across:

- `pom.xml`
- `src/main/resources/plugin.yml`
- `README.md`
- Release notes when present

## Architecture Map

- Bootstrap/lifecycle: `ContractPlugin`
- Config/defaults: `src/main/resources/config.yml`
- Commands: `command/ContractCommand`
- GUI: `gui/ContractGui`
- Service/state machine: `service/ContractService`, `service/ServiceResult`
- Economy: `economy/EconomyService`
- Storage: `storage/ContractStorage`, `PendingTransactionStore`, `EventLog`
- Models: `model/*`
- Localization: `config/LanguageManager`, `src/main/resources/lang/zh_CN.yml`
- Utilities: `util/Text`
- Tests: `src/test/java/org/cubexmc/contract/**`

## Hard Boundaries

- Contract state transitions should go through `ContractService`.
- Vault economy operations should go through `EconomyService`; command and GUI
  code should not call Vault directly.
- Escrow recovery and idempotence must preserve `PendingTransactionStore` and
  `EventLog` semantics.
- Contract persistence should go through `ContractStorage`; do not duplicate YAML
  schema rules in command or GUI code.
- GUI classes should render and route click intent; business rules stay in the
  service layer.
- Player-visible messages should go through `LanguageManager` and locale files.
- Config changes require default resource updates and README synchronization.
- Scheduled cleanup and flush behavior must preserve shutdown save guarantees.

## Data And Config Surfaces

Default resources:

- `config.yml`
- `plugin.yml`
- `lang/zh_CN.yml`

Runtime data:

- `plugins/Contract/contract.yml`
- `plugins/Contract/pending-transactions.yml`
- `plugins/Contract/events.log`

Any config or data schema change requires:

- Default resource update.
- Compatibility read path or migration when old data exists.
- Tests for old and new shapes where practical.
- README update and operator-facing rollback notes when release-impacting.

## Human Interaction Surfaces

Contract UX happens through:

- `/contract` and `/ct` commands.
- Contract board GUI.
- Contract creation, list, mine, info, accept, submit, approve, cancel, dispute,
  and admin settlement flows.
- Permission failures and admin boundaries.
- Localized chat messages and GUI item text.

Human-facing changes should preserve:

- Clear contract IDs and title/description truncation behavior.
- Predictable money movement at create, accept, submit, approve, cancel,
  dispute, refund, and admin settlement points.
- Explicit failure reasons for permission, status, ownership, deadline, funds,
  and Vault provider problems.
- Confirmation or clear syntax for destructive admin actions.
- Localized messages for changed text.

## High-Risk Runtime Flows

Treat these as R3 unless proven narrower:

- Vault charge, deposit, refund, commission, and escrow recovery.
- Contract state transitions across open, accepted, submitted, approved,
  cancelled, disputed, expired, and admin-settled states.
- Pending transaction recovery after restart.
- Contract and pending YAML schema changes.
- Scheduled expiry cleanup and submitted auto-approval.
- GUI actions that trigger economy or state changes.
- Admin pay, refund, close, reload, and view-all flows.

## Verification Defaults

Standard commands:

- Targeted unit test: `mvn "-Dtest=ClassNameTest" test`
- All unit tests: `mvn test`
- Quality/package gate: `mvn verify`
- Release artifact: `mvn clean verify package`

Current expected gate:

- `mvn test` runs the existing model and utility tests.
- `mvn verify` compiles, runs tests, and builds the jar.

Manual runtime checks:

- Use README command and funds-rule flows for live server scenarios.
- Prioritize Vault provider setup, create/accept/submit/approve, cancellation,
  dispute, admin settlement, reload, and restart recovery.

## Documentation Sync Map

- Command, permission, or GUI change: README, `plugin.yml`, locale files.
- Config change: `config.yml`, README.
- Storage or schema change: `ContractStorage`, `PendingTransactionStore`,
  migration notes, README when operator-visible.
- Economy or state-machine change: `ContractService`, `EconomyService`, tests,
  README funds rules.
- Platform/dependency change: `pom.xml`, `plugin.yml`, README, release notes.

## Evidence Expectations

Final handoff should state:

- Risk class.
- Files changed.
- Tests run.
- Manual regression scope.
- Documentation updated or intentionally unchanged.
- Residual risk, especially for real server behavior not covered by unit tests.
