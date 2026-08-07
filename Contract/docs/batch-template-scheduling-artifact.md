# Batch Stacking, Template Library, And Scheduled Publication Artifact

Status: approved design; implementation not started  
Owner scope: `Contract` only  
Next executable phase: P1 — stacked batch display and submitted/total progress

## 1. Source Request

The player request is:

1. Stack identical batch tasks as `item × N`; accepting one task decrements N.
2. Show submitted progress as `submitted / total`, for example `12/32`.
3. Save and load reusable contract templates, with optional scheduled
   publication.

The goal is to reduce repeated setup and let authorized batch publishers manage
their own tasks efficiently without granting admin settlement or view-all
authority.

## 2. Frozen Semantics

These definitions control implementation and tests:

- "Accept/claim one" means accepting one child SERVICE contract from a batch.
  It does not mean withdrawing an item from a shared warehouse.
- Only children with an explicit shared `batch-id` (or a future explicit
  publication identity) may stack. Matching title, item, reward, owner, or
  description is insufficient.
- `total` is the immutable planned batch size.
- `available` is the number of children currently in `OPEN`.
- `accepted` is the number of children whose `acceptedAt` is non-null.
- `submitted` is the number of children whose `submittedAt` is non-null.
  Completed or disputed-after-submission children remain submitted.
- `completed` is the number currently in `COMPLETED`.
- Public stack quantity displays `available`, not `total` or `submitted`.
- One accepted child retains its own contract ID and follows the existing
  submit, approve, dispute, settlement, and audit flows.
- A template contains reusable terms/defaults, never live state, escrow, or a
  promise that money/items are already available.
- Initial scheduling scope is one-time publication at a specified server time.
  Recurring schedules, automatic replenishment, bulk approval, and a general
  operator console are non-goals until the three requested capabilities ship.

If the product owner later states that "claim" means a shared item-pool
withdrawal, stop: that is a different storage/economy design and this artifact
must be revised before implementation.

## 3. Current Implementation Substrate

Already implemented:

- `ContractService.createBatch` creates independent SERVICE child contracts.
- Metadata records `batch-id`, `batch-index`, `batch-size`, repeat policy, and
  optional repeat cooldown.
- `BatchAcceptanceStore` preserves ONCE/COOLDOWN history.
- `Contract.submittedAt` is persisted for manual and system-verified
  submissions.
- Batch creation already validates count, permission, open limit, money, item
  divisibility, and escrow withdrawal.
- `CubexScheduler` and crash-aware economy transaction infrastructure exist.

Implemented on `codex/contract-batch-templates-scheduling`:

- pure `BatchSummary` projection, explicit-ID hall grouping, stack decrement,
  all four counters, child drill-down, and atomic accept-one;
- private/server template model, atomic YAML store, permissioned library,
  load/delete/visibility actions, and save-from-draft;
- typed `publishAt`, `SCHEDULED` persistence, escrow-at-signing, deadline from
  publication, startup/periodic idempotent activation, and full pre-publication
  cancellation refund;
- config v5 -> v6 migration, permissions, locale status keys, README and tests.

Runtime manual GUI/economy testing on a Paper server remains release evidence,
not an implementation gap.

## 4. Target Player Experience

### Public hall batch card

```text
[DIAMOND ×20] 收购钻石
已提交 12/32
进行中 5 · 已完成 9 · 争议 1
每份奖励 $500 · 每人一次
任务组 #8F2A
```

- Use the objective target material when it resolves to a safe stackable icon;
  otherwise use the SERVICE paper icon.
- The item amount is `available`, capped to a client-safe stack amount. The
  exact number always appears in the name/lore.
- A batch consumes one hall slot and one pagination entry.
- A normal player opens a batch detail view whose primary action is
  "领取 1 份".
- The publisher sees status counts and child-contract drill-down.
- A batch with zero available children leaves the public OPEN view but remains
  visible in the publisher's relevant views/history.
- Blocked acceptance remains visible with a precise reason: already accepted,
  active child, cooldown remaining, permission, capacity, or the final child
  being taken by another player.

### Template flow

```text
/ct -> 模板库 -> 我的模板/服务器模板 -> 选择模板
    -> 加载为草稿 -> 补充本次变量 -> 预览 -> 签署发布
```

- Loading never charges money or removes items.
- Item-reward templates require the actual item in hand at signing.
- Editing/deleting a template cannot mutate existing contracts or scheduled
  snapshots.
- Important actions use visible buttons; hidden click gestures are optional
  shortcuts only.

### Scheduled publication flow

```text
加载模板或填写草稿 -> 定时发布 -> 输入服务器时间
-> 显示绝对时间和相对时间 -> 预览总托管 -> 一次签署
-> 到点从 SCHEDULED 变为 OPEN
```

- Funds/items are reserved at scheduling time so an offline owner does not
  cause publication failure.
- Deadline begins at actual publication time.
- Cancellation before publication refunds all unpublished escrow and the
  reserved creation fee.
- Restart must not duplicate child contracts, withdrawal, or publication.

## 5. Implementation Phases

### P0 — Profile, Artifact, And Harness

Status: complete when this artifact and the linked agent docs are committed.

Deliverables:

- current Gradle/Paper project profile;
- this approved feature artifact;
- Gradle-based verification matrix and handoff template;
- AGENTS routing to the active artifact.

No runtime behavior changes.

### P1 — Stacked Display And Progress Counter

Risk: R2 for query/rendering; R3 for the competing-click accept path.

Proposed code shape:

- `model/BatchSummary.kt`: immutable summary with representative child and
  counts.
- `service/BatchQueryService.kt`: group only explicit batch identities and
  produce viewer-independent summaries.
- `gui/HallEntry.kt`: `SingleContract` or `Batch` entry.
- `ContractRenderer.batchItem`: render exact availability and progress.
- `ContractGui`: paginate entries, open batch details, refresh after accept.
- `ContractService.acceptOneFromBatch`: synchronized selection plus existing
  acceptance rules; select the eligible OPEN child with the earliest deadline
  and return its concrete ID.

Do not duplicate the existing `accept` business rules. Refactor a private
shared acceptance path if needed.

Legacy compatibility:

- Existing `batch-id`/`batch-size` data groups without migration.
- Singles and malformed/mixed batches render independently rather than being
  merged by guesswork.
- Do not add persistent batch files in the first slice unless a failing test
  proves derivation cannot meet active-view behavior.

Required tests:

- 32 matching children -> one summary, total 32, available 32.
- accept one -> available 31 and one concrete child becomes IN_PROGRESS.
- 12 non-null `submittedAt` values -> 12/32 after a mix of SUBMITTED,
  COMPLETED, and DISPUTED states.
- identical terms with different batch IDs never merge.
- malformed batch metadata degrades to independent cards.
- competing claims for the final child yield one success and one clear failure.
- ONCE/COOLDOWN and active-contract limits remain enforced.
- single-contract hall behavior remains unchanged.

Acceptance criteria:

- one batch occupies one public slot;
- claimant sees the quantity decrement immediately after success;
- stale viewers cannot double-claim;
- exact submitted/total semantics match Section 2;
- no economy or storage-schema behavior changes.

### P2 — Template Library

Risk: R3 because it adds persistent user-authored data and permissions.

Domain model:

- Introduce immutable `ContractSpec`; do not persist GUI `CreateDraft`.
- `ContractTemplate` contains ID, owner UUID, unique owner-scoped name,
  visibility (`PRIVATE` or `SERVER`), spec, timestamps, and schema version.
- `CreateDraft` converts to/from `ContractSpec` for the GUI.

Persistence and service:

- `storage/ContractTemplateStore.kt` owns versioned `templates.yml`.
- `service/ContractTemplateService.kt` owns validation, ownership,
  name/ID resolution, save/load/update/delete, and server-template authority.
- Template data is saved atomically. A malformed template is isolated and
  reported without discarding valid entries.

Permissions:

- `contract.template.use`
- `contract.template.manage`
- `contract.template.admin`

Required tests:

- spec round trip and validation;
- save/load/update/delete across restart;
- unique names per owner and unambiguous IDs;
- foreign/private and server-template permission denial;
- load produces a draft but performs no Vault or inventory operation;
- editing/deleting does not alter existing contract snapshots;
- legacy startup without `templates.yml` remains clean.

Acceptance criteria:

- players can save a valid draft, load it later, and publish through the normal
  preview/signing path;
- server templates are usable but only admins can mutate them;
- item reward still requires real escrow at signing;
- README, plugin permissions, both locales, and artifact status are updated.

### P3 — Optional One-Time Scheduled Publication

Risk: R3 runtime-critical.

Initial model:

- Add `SCHEDULED` to `ContractStatus`.
- Add a typed nullable `publishAt` field to `Contract`; do not hide lifecycle
  time only in metadata.
- Scheduled signing pre-creates hidden child contracts and reserves the full
  finite batch escrow. It does not generate or withdraw again at activation.
- Set `expiresAt = publishAt + configured duration`.
- Scheduled children reserve publisher capacity so accepted schedules can open
  at the promised time without silently exceeding limits.

Lifecycle:

1. Validate schedule window, permission, capacity, count, and escrow.
2. Write pending withdrawal intent.
3. Withdraw once and atomically persist SCHEDULED children.
4. Clear pending intent.
5. Scheduler scans due children and idempotently transitions SCHEDULED to OPEN.
6. A pre-publication cancellation returns reward/items and reserved creation
   fee through the existing recovery/audit boundaries.

Permissions/config proposals:

- `contract.schedule.create`
- `limits.max-scheduled-contracts`
- `scheduling.max-days-ahead`
- `scheduling.scan-interval-seconds`

These keys do not exist until P3 and require a config-version migration.

Required tests:

- legacy contract data without `publish-at` loads unchanged;
- scheduled children are absent from the public hall before due time;
- activation opens existing IDs exactly once;
- restart before and after due time does not duplicate withdrawal or children;
- offline owner does not block activation;
- pre-publication cancellation fully refunds reserved assets and fee;
- deadline is relative to publication, not signing;
- scheduled batch appears as one correct stack when opened;
- config migration and permission denial are explicit.

Required live smoke:

- Paper with Vault and a real economy provider;
- schedule money and item batches;
- owner logout, restart, due-time activation;
- final-child competing accept;
- pre-publication cancellation and inventory/fund verification;
- reload/shutdown flush behavior; Folia when the scheduler path changes shared
  execution assumptions.

Acceptance criteria:

- optional one-time scheduling works from template-loaded and fresh drafts;
- escrow conservation and event audit remain intact;
- activation is exactly once across restart;
- no recurring or replenishment semantics are introduced.

## 6. Persistent Task-Batch Follow-Up

P1 may derive live progress from child contracts. Before retention or future
long-running schedules can remove children while history is still displayed,
introduce a versioned `TaskBatch` aggregate with immutable total, child IDs,
publication identity/time, and final counter snapshot.

Do not add this aggregate speculatively in P1. Add it when P3 or a retention
test demonstrates the need, with reconstruction from existing batch metadata.

## 7. Invariants

- Money plus item escrow is conserved through create, schedule, accept,
  cancel, settlement, restart, and recovery.
- Every child has one stable contract ID.
- Batch identity is explicit and never inferred from presentation fields.
- `submitted` never decreases because status changes after submission.
- One child can be accepted by at most one player.
- Existing SERVICE, WAGER, and PARTNERSHIP flows remain compatible.
- Commands and GUI use the same service paths.
- Player-visible messages exist in both locales.
- Each phase is independently buildable and committed separately.

## 8. Verification And Handoff

Use `docs/agent-verification-matrix.md` and
`docs/agent-evidence-template.md`. At the end of every phase update:

- phase status and completed acceptance criteria;
- exact Gradle commands and results;
- manual runtime coverage and gaps;
- migration/rollback notes;
- residual risk;
- next executable phase or slice.

Before implementation, inspect the current branch and Contract working tree.
At artifact creation time the observed branch was `kotlin/railway`; do not mix
Contract implementation with Railway migration work or move/reset parallel WIP
without explicit user direction.

## 9. Claude Handoff

Implementation is complete. Continue from the verification matrix: run the
Paper smoke scenarios for simultaneous batch acceptance, template permission
boundaries, scheduled money/item escrow, restart before/after due time, and
pre-publication cancellation before approving release.

## 10. Implementation Evidence (2026-08-03)

- Branch: `codex/contract-batch-templates-scheduling` (isolated from
  `kotlin/railway`).
- `.\gradlew.bat :Contract:test`: PASS, 102 tests, 0 failures/errors.
- `.\gradlew.bat :Contract:build`: PASS; deployable
  `Contract/build/libs/contract-0.1.0.jar` generated.
- `.\gradlew.bat :Contract:jarGate`: PASS; Java 17 class major 61,
  unrelocated Kotlin 0.
- Automated coverage includes explicit-ID aggregation, all progress counters,
  accept-one selection, template term round-trip/privacy/limits, typed schedule
  persistence, deadline calculation, and activation idempotence.
- Manual Paper runtime verification is still required for simultaneous player
  clicks, Vault money/item escrow, GUI copy/layout, Folia scheduling, and
  restart immediately around a due time.

Rollback: config migration creates the normal backup. `publish-at` is an
additive YAML key, but an older build cannot parse the new `SCHEDULED` enum.
Before rolling back, cancel scheduled contracts (full refund) or allow them to
activate and verify no `SCHEDULED` records remain. Preserve `templates.yml` if
templates should be restored after returning to this version.

## 11. Globalization Pass (2026-08-06)

Release-blocking gap found during review: the batch/template/scheduling UI added
`ui.*` keys, but the rest of the plugin still carried roughly 540 lines of
hard-coded Chinese, so `language: en_US` produced a half-translated plugin.

Changes:

- every player-visible string in `ContractGui`, `ContractRenderer`,
  `DialogInputService`, `ContractCommand`, `ObjectiveListener` and all ~135
  `ServiceResult.fail` reasons in `ContractService` now resolve through
  `lang().ui(key, placeholders)` at render time, so `/contract admin reload`
  applies a language change without a restart;
- new `objectives`, `objective-targets` and `objective-prompts` locale sections
  replace the three hard-coded `when (ObjectiveType)` label tables;
- `CreateDraft.validate` returns a `DraftProblem(key, placeholders)` instead of
  a rendered Chinese sentence, keeping it a pure function while the GUI owns
  the locale;
- `ContractTerms.preview` and `PendingAction.create` take their localized text
  from the caller rather than embedding it;
- `claimStoredItems` keys its rollback branch off a typed `StoredItemKind`
  instead of comparing a display string;
- `contract.bypass.fee` is declared in `plugin.yml` (it was checked in
  `ContractService` but never registered, so it had no default and was invisible
  to permission plugins);
- lang schema `2 -> 3` with `MergeBundledLangStep`, which fills in every key the
  jar defines and the server's file lacks without overwriting operator edits —
  without it an upgraded server would render raw key names everywhere.

`LanguageParityTest` now enforces the contract: identical key sets across
locales, identical placeholder sets per key, and every `ui.*` key referenced in
Kotlin actually defined.

Evidence:

- `.\gradlew.bat :Contract:clean :Contract:build :Contract:jarGate`: PASS.
- `:Contract:test`: PASS, 106 tests, 0 failures/errors.
- `jarGate`: Java 17 class major 61, unrelocated Kotlin 0, relocated 1029.
- Residual: still no automated coverage of real Paper rendering. The runtime
  scenarios in section 10 remain outstanding, and should now be run **twice**,
  once per locale, to confirm no screen falls back to raw keys.
