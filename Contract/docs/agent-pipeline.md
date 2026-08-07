# Reusable Agent Pipeline

This pipeline is project-neutral. Concrete build commands, runtime constraints,
artifact paths, and active feature decisions live in
`docs/agent-project-profile.md` and task artifacts.

## Pipeline Stages

### 1. Intake

Classify the request:

- feature, bugfix, refactor, compatibility, documentation, release, or
  investigation;
- user-visible or internal;
- runtime-impacting or static-only;
- reversible or migration-affecting;
- narrow module or cross-cutting.

Output: one-line task class and initial risk class.

### 2. Context Assembly

Read the smallest sufficient context:

- repository and project `AGENTS.md` files;
- `docs/agent-project-profile.md`;
- `docs/agent-verification-matrix.md`;
- the active task artifact;
- the source, tests, and operator docs that own the behavior.

Output: current behavior, ownership boundary, active phase, and likely files.

### 3. Design And Risk Note

Before edits, record:

- intended behavior and explicit non-goals;
- invariants that must not break;
- storage/economy/scheduler compatibility implications;
- tests and manual checks that prove the change;
- documentation that must remain synchronized.

For high-risk work, locate or add a failing/characterization test before
changing implementation when practical.

### 4. Implementation

- Implement one artifact phase or smaller slice at a time.
- Reuse existing service, store, scheduler, and GUI boundaries.
- Keep IO, runtime scheduling, persistence, and presentation explicit.
- Avoid unrelated formatting and opportunistic refactors.
- Preserve backward compatibility unless the artifact explicitly changes it.
- Do not silently expand scope when a later phase looks convenient.

### 5. Verification

Use `docs/agent-verification-matrix.md`:

- targeted tests first;
- full module tests after shared behavior changes;
- module build and artifact gate for persistence, scheduler, dependency,
  packaging, or release-impacting work;
- manual runtime scenarios when mocks cannot demonstrate the actual risk.

Output: exact command, result, meaningful summary, and remaining gap.

### 6. Documentation Sync

Update documentation in the same slice for changes to:

- commands, GUI, permissions, messages, or configuration;
- public API or compatibility claims;
- storage schema, migration, recovery, or rollback behavior;
- economy/state-machine behavior;
- artifact phase status and handoff point.

### 7. Evidence And Handoff

Finish with:

- artifact and completed phase;
- changed files and reason;
- tests and runtime checks;
- migration/rollback notes;
- residual risk;
- the next executable slice.

Use `docs/agent-evidence-template.md` for R3/R4 changes or cross-agent handoff.

## Risk Classes

### R0 Static

Docs, comments, formatting, or test-only changes with no runtime impact.

Expected evidence: document consistency/link review or the relevant no-op
validation.

### R1 Local

Pure model/util behavior with narrow ownership and no persistence, economy,
permissions, scheduler, or public API impact.

Expected evidence: targeted project-native tests.

### R2 Shared

Service, command, GUI, localization, or configuration behavior used by
multiple flows but without a runtime-critical boundary.

Expected evidence: targeted tests plus the full module test gate.

### R3 Runtime-Critical

Persistence, migration, economy, permissions, scheduler/threading, item/world
access, public API, dependency shading, or deployable artifact behavior.

Expected evidence: targeted tests, full module build, artifact gate where
applicable, and a named manual runtime scope.

### R4 Release-Critical

Version, release artifact, platform support claim, compatibility matrix, or
security/supply-chain change.

Expected evidence: clean release-grade build, artifact inspection, startup
smoke, release checklist, and rollback notes.

## Agent Harness Expectations

- Name the artifact, phase, and risk class.
- Inspect branch and scoped working-tree state before edits.
- Explain why each file is touched.
- Prefer deterministic project-native commands.
- Treat failing checks as first-class evidence.
- Keep active artifact status synchronized with implementation.
- Leave enough durable evidence for Claude Code or Codex to continue without
  reconstructing decisions from chat history.
