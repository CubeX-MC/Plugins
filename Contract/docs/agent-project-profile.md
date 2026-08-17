# Contract Agent Project Profile

This file is the source of truth for Contract-specific build, runtime,
architecture, risk, and documentation facts. Reusable skills and the generic
agent pipeline must not duplicate these details.

## Project Identity

- Project: `Contract`, a subproject of the CubeX-Plugins Gradle monorepo.
- Type: independently deployable Minecraft Paper plugin.
- Sources: Kotlin plus a small Java surface; shared `cubex-*` modules are
  shaded into the deployable plugin jar.
- Main class: `org.cubexmc.contract.ContractPlugin`.
- Version: `0.1.0`, from `Contract/build.gradle.kts`.
- Core promise: a player-to-player contract board with Vault-backed escrow,
  batch SERVICE publication, submissions, approval, cancellation, disputes,
  mediation, and admin settlement.
- Active feature artifact:
  `docs/batch-template-scheduling-artifact.md`. Read it before changing batch
  display, templates, publication scheduling, or their persistence model.

## Build And Artifact Contract

- Build system: Gradle wrapper from the monorepo root. Do not use the stale
  Maven commands that appeared in older Contract documentation.
- Convention plugin: `cubex-kotlin-plugin` from `buildSrc`.
- Development toolchain: JDK 21.
- Emitted project bytecode: Java 17 (class major 61) for both Java and Kotlin.
- Windows invocation: PowerShell plus `.\gradlew.bat`; the repository path
  contains a space and Git Bash invocation is unsupported.
- Deployable artifact:
  `Contract/build/libs/contract-<version>.jar` (Shadow jar, no classifier).
- Non-deployable artifact:
  `Contract/build/libs/Contract-<version>-plain.jar`. Never hand this jar to an
  operator as the plugin artifact.
- `build` depends on `shadowJar`; `jarGate` verifies `plugin.yml`, project
  bytecode level, Kotlin relocation, and absence of a bundled kotlin-reflect
  implementation.
- Kotlin stdlib and FoliaLib are relocated under the Contract library
  namespace. Adventure is provided by Paper and must remain excluded from the
  shaded jar.

Canonical commands from the monorepo root:

```powershell
.\gradlew.bat :Contract:test
.\gradlew.bat :Contract:test --tests "org.cubexmc.contract.SomeTest"
.\gradlew.bat :Contract:build
.\gradlew.bat :Contract:shadowJar
.\gradlew.bat :Contract:jarGate
```

Use `:Contract:build` as the standard module gate and `:Contract:jarGate` when
the deployable artifact, dependencies, relocations, resources, or compatibility
claims are in scope.

## Runtime Matrix

- Compile API: Paper API `1.21.11-R0.1-SNAPSHOT`.
- Development server task: Paper `1.21.8`.
- Plugin API version in `plugin.yml`: `1.18`.
- Runtime target: Paper. Do not restore a pure-Spigot support claim without a
  deliberate compatibility project.
- Dialog path: Paper Dialog API on 1.21.6+; guarded by `DialogSupport` so older
  Paper builds use the inventory/chat fallback without loading Dialog classes.
- Required plugin dependency: Vault.
- Required runtime service: a Vault-compatible economy provider.
- Economy load-order soft dependencies: Essentials, EssentialsX, and CMI.
- Optional integration: Reputations. `softdepend` controls load order only;
  Contract has no project/API dependency on it and remains standalone.
- Folia: `folia-supported: true`; scheduling is routed through
  `cubex-scheduler`/FoliaLib. New scheduler or world-access behavior still
  requires an appropriate Paper/Folia runtime smoke check.

Keep platform and artifact claims synchronized across:

- `Contract/build.gradle.kts`
- `Contract/src/main/resources/plugin.yml`
- `Contract/README.md`
- `Contract/CHANGELOG.md`
- this profile

## Architecture Map

- Bootstrap, lifecycle, migrations, scheduling: `ContractPlugin`.
- Commands and tab completion: `command/ContractCommand`.
- GUI navigation and action routing: `gui/ContractGui`.
- Pure GUI presentation: `gui/ContractRenderer`, `gui/GuiItems`.
- GUI framework: `gui/framework/Menu`, `InventoryButton`, `MenuRegistry`.
- Input backends: `ChatInputService`, guarded `DialogInputService`.
- Creation draft: `gui/CreateDraft`.
- Contract state machine and economy orchestration: `service/ContractService`.
- Batch repeat rules: `service/BatchRepeatRules`.
- Vault boundary: `economy/EconomyService`.
- Contract persistence: `storage/ContractStorage`.
- Economy recovery journal: `storage/PendingTransactionStore`.
- Batch acceptance history: `storage/BatchAcceptanceStore`.
- Audit log: `storage/EventLog`.
- Local reputation state: `storage/ReputationStore` and `reputation.yml`.
- Optional reputation delta adapter: `integration/reputation/ReputationsMirror`,
  connected through shaded `cubex-integrations` and the provider class loader.
- Models: `model/*`.
- Localization: `config/LanguageManager`, `resources/lang/zh_CN.yml`, and
  `resources/lang/en_US.yml`.
- Shared scheduling/config/i18n/runtime support: shaded `modules/cubex-*`.

## Hard Boundaries

- Contract state transitions go through `ContractService`.
- Vault operations go through `EconomyService`; commands and GUI must not call
  Vault directly.
- Escrow recovery and idempotence preserve `PendingTransactionStore` and
  `EventLog` semantics.
- Contract persistence goes through `ContractStorage`; do not reproduce YAML
  schema rules in GUI or command code.
- GUI code renders state and routes intent. Batch selection, repeat rules,
  ownership checks, and scheduled activation belong in service/query layers.
- Player-visible text goes through `LanguageManager` and both locale files.
- Config changes require a versioned migration step, default resource update,
  tests, and README synchronization.
- Scheduling uses `CubexScheduler`; do not introduce raw Bukkit schedulers.
- Adventure remains server-provided; do not shade or relocate `net.kyori`.
- Each implementation slice must remain independently buildable and must not
  mix unrelated gameplay, refactor, or compatibility changes.
- Optional plugin adapters must preserve local behavior, must not compile
  against or shade provider APIs, and must treat provider failures as
  best-effort degradation. Transactional integrations require a separate,
  idempotent domain contract.

## Current Batch Substrate

- SERVICE batch creation already creates independent child contracts.
- Children are joined by metadata keys `batch-id`, `batch-index`, and
  `batch-size`.
- Repeat policy is stored as `repeat-policy` plus optional
  `repeat-cooldown-hours`.
- `BatchAcceptanceStore` enforces ONCE/COOLDOWN history across children.
- `Contract.submittedAt` is persisted and is the authoritative definition of
  "has been submitted" for batch progress; current status alone is not.
- The hall groups children only by explicit `batch-id`, renders the available
  stack amount and accepted/submitted/completed counters, and accepts exactly
  one available child through `ContractService.acceptOneFromBatch`.
- The template library persists reusable terms in `templates.yml`; templates
  never contain live contract state, escrow, or a scheduled timestamp.
- One-time scheduled SERVICE publication persists typed `publishAt`, reserves
  escrow at signing, and idempotently changes `SCHEDULED` children to `OPEN`.

Do not infer grouping from matching titles, items, rewards, or descriptions.
Only an explicit batch/publication identity may merge children.

## Data And Config Surfaces

Default resources:

- `config.yml` (`config-version: 6`)
- `plugin.yml`
- `lang/zh_CN.yml`
- `lang/en_US.yml`

Current runtime data:

- `plugins/Contract/contract.yml`
- `plugins/Contract/pending-transactions.yml`
- `plugins/Contract/batch-acceptance.yml`
- `plugins/Contract/templates.yml`
- `plugins/Contract/reputation.yml`
- `plugins/Contract/events.log`

The active artifact defines the shipped batch/template/scheduling invariants.
Recurring schedules, replenishment and bulk settlement remain out of scope.

Any schema change requires:

- a compatibility read path or migration for existing data;
- tests for legacy and new shapes;
- synchronous/crash-safe handling when escrow ownership can change;
- rollback notes when an older plugin version cannot safely read the new data;
- README/CHANGELOG updates when the change becomes user-visible.

## Human Interaction Surfaces

- `/contract` and `/ct` commands.
- Contract hall, status views, details, creation, confirmation, inbox, and
  admin workbench.
- SERVICE batch count and repeat-policy controls.
- Accept, submit, claim, approve, cancel, dispute, mediate, and admin-settlement
  actions.
- Dialog API on supported Paper versions; inventory/chat fallback elsewhere.
- Permission failures and localized player feedback.

Human-facing changes preserve:

- explicit contract or task-batch IDs when names can collide;
- predictable money and item movement before confirmation;
- visible reasons for permission, status, ownership, deadline, repeat-policy,
  capacity, funds, storage, and Vault failures;
- one confirmation for destructive or financial actions;
- no hidden gesture as the only route to an important action;
- consistent semantics across GUI and commands.

## Risk Hotspots

Treat these as R3 unless a narrower boundary is demonstrated:

- Vault withdrawal, deposit, refund, commission, and escrow recovery.
- Contract transitions among OPEN, PENDING_ACCEPT, IN_PROGRESS, SUBMITTED,
  DISPUTED, COMPLETED, CANCELLED, and EXPIRED.
- Batch acceptance selected from a stacked card under competing clicks.
- Contract, batch, template, schedule, or pending-transaction schema changes.
- Scheduled activation, shutdown flush, reload, and restart recovery.
- Item removal, delivery, claim, rollback, or scheduled item escrow.
- GUI actions that trigger economy or state changes.
- Admin pay, refund, close, reload, and view-all flows.

## Documentation Sync Map

- Command, permission, or GUI change: README, `plugin.yml`, both locale files.
- Config change: `config.yml`, migration step/tests, README.
- Storage/schema change: owning store, migration tests, artifact/README, and
  rollback notes.
- Economy/state-machine change: `ContractService`, `EconomyService`, recovery
  tests, README funds rules.
- Platform/dependency/artifact change: build file, `plugin.yml`, README,
  CHANGELOG, this profile, and `jarGate` evidence.
- Active artifact phase completion: update its status, evidence, residual risk,
  and next handoff point in the same change.

## Handoff Expectations

Before implementation, record the current branch and Contract-specific working
tree. This repository commonly contains parallel work. Do not reset, move, or
include unrelated changes. Stage Contract work with an explicit Contract path.

Every handoff must state:

- artifact and phase;
- risk class;
- files changed and why;
- exact Gradle commands and results;
- manual runtime coverage and gaps;
- storage/economy rollback expectations;
- residual risk and the next executable slice.
