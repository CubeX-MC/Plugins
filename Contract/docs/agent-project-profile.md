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
- Core promise: a player-to-player contract board with Vault/item escrow,
  batch SERVICE publication, SALE item-for-money exchange, submissions,
  approval, cancellation, disputes, mediation, and admin settlement.
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
- Provider-owned optional API: `api/escrow/ContractEscrowService`; its Regions
  implementation accepts only funded `IN_PROGRESS` WAGERs and persists lock/
  terminal operation metadata inside `contract.yml`.
- Models: `model/*`.
- ALLIANCE (player creation not enabled): `model/AllianceAgreement` holds immutable
  UUID-scoped funded signatures/approvals; `model/AlliancePayoutPlan` computes explicit
  source/recipient UUID principal allocations. It does not call Vault or write a journal.
  `Contract.createAlliance` is a model factory; `ContractService.createAlliance` and
  its ALLIANCE accept dispatch call `service/AllianceFundingService` under the service
  monitor for actual creator/member escrow. Terminal payout execution is not connected.
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
- A region-funded WAGER cannot use ordinary settlement paths. The external
  transaction operation id owns both its lock and terminal call and is persisted
  before payout; incomplete or conflicting terminal attempts fail closed as
  manual review instead of paying twice.

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

`contract.yml` stores live physical escrow in participant ITEM assets for new
records. Terminal item entitlements use the optional nested shape
`item-claims.<recipient-role>.<source-role>`. Legacy SERVICE
`delivery-items`/`reward-items` pools remain readable and writable so existing
saves and a downgrade to the pre-entitlement `0.1.0` behavior still retain the
SERVICE items it understands. An older runtime ignores `item-claims`; therefore
do not downgrade while any role-owned terminal claim outside those SERVICE
pools remains unresolved. SALE uses these role-owned claims for success,
refund, timeout, and mediated outcomes.

ALLIANCE model records add `alliance.version: 1`, `alliance.signatures` entries
(`uuid`, `accepted-at`), and an `alliance.approvals` UUID list. Participant MONEY
assets remain the sole principal terms; signatures distinguish funded members
from invitees. Only OWNER plus two or more distinct ALLY members are accepted.
Amounts are positive whole cents. Every signature precedes the acceptance deadline;
the creator signature matches `created-at`. Malformed ALLIANCE records stop loading
without dropping the record or selecting a stale backup; the previous in-memory
database remains untouched. Existing SERVICE/WAGER/PARTNERSHIP/SALE records gain no
new required fields. Do not downgrade a save containing ALLIANCE records: earlier
runtimes cannot read `PENDING_ACCEPT_MULTI` / `ALL_APPROVE` and may skip them.

Principal plans return each funded member's own stake on refund, require unanimous
signing and approval for success, and split a named defaulter's stake among all other
members on a disputed breach. Integer-cent division plus UUID-sorted remainder
allocation conserves every source pool; fees/commission policy and terminal execution
remain outside this calculation. Funding now persists the signature plus matching
`metadata.alliance-funding-op-<uuid>`; the final signature activates the contract.
An already funded invited member counts toward `limits.max-active-accepted-contracts`
even while other signatures are pending. Creation uses `limits.max-open-contracts`;
the service checks existing `contract.create` / `contract.accept` permissions.

ALLIANCE WITHDRAW journal entries add `funding-phase`: PREPARED before Vault,
WITHDRAWN after confirmed withdrawal, REFUNDING before compensation, REFUNDED after
confirmed compensation, or REJECTED after a definite withdrawal failure. Recovery
matches the member UUID, amount and operation ID against a persisted signature;
it never relies on global contract status alone. Confirmed uncommitted withdrawal
can be refunded; PREPARED/REFUNDING are ambiguous and remain for manual review with
further funding blocked for that player/contract. Missing acceptance contracts or
operation/signature conflicts also remain for review. Legacy unphased entries keep
their existing recovery behavior and constructor shape. The shared journal uses
strict YAML reads and same-directory atomic replacement without stale-backup fallback;
unsupported atomic moves fail the write rather than downgrading to an unsafe overwrite.
Do not downgrade with phased entries: older recovery cannot interpret them safely.

Terminal service work must persist settlement intent before external effects and
block settlement/retention while funding is unresolved; the old role-based executor
must not execute ALLIANCE. See PLAN §5.1, `alliance-model-evidence.md`, and
`alliance-funding-evidence.md`.

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
- SALE creation from the seller's complete main-hand stack, named-buyer
  acceptance, mutual approval, and item claim discovery.
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
