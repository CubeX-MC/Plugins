# Reusable Agent Pipeline

This pipeline is for agent-assisted plugin development. It is intentionally
project-neutral: project facts, runtime quirks, commands, and release rules live
in `docs/agent-project-profile.md`.

## Source Models

The workflow combines practices with broad industrial or academic support:

- Continuous delivery: small batches, fast feedback, test automation, and
  keeping software deployable.
- Risk-based secure development: identify risk before implementation and scale
  evidence to the blast radius.
- Supply-chain integrity: reproducible builds, dependency hygiene, and release
  artifacts that can be traced to verified source.
- Human-centered design: model the operator/player workflow before changing
  command, GUI, message, or permission behavior.
- Evidence-based iteration: every agent turn should leave enough evidence for a
  reviewer to understand what changed, what passed, and what remains uncertain.

References:

- DORA continuous delivery capabilities: https://dora.dev/capabilities/continuous-delivery/
- DORA software delivery metrics: https://dora.dev/guides/dora-metrics/
- NIST SSDF SP 800-218: https://csrc.nist.gov/pubs/sp/800/218/final
- SLSA supply-chain framework: https://slsa.dev/
- OWASP SAMM: https://owasp.org/www-project-samm/

## Pipeline Stages

### 1. Intake

Classify the request before reading widely:

- Feature, bugfix, refactor, compatibility update, documentation, release, or
  investigation.
- User-visible or internal.
- Runtime-impacting or static-only.
- Reversible or migration-affecting.
- Narrow module or cross-cutting.

Output: one-line task class and initial risk class.

### 2. Context Assembly

Read only the minimum context needed to make a safe change:

- `AGENTS.md`
- `docs/agent-project-profile.md`
- `docs/agent-verification-matrix.md`
- Relevant architecture, API, compatibility, regression, or release docs.
- The smallest set of source and test files that own the behavior.

Output: current behavior summary and likely ownership boundary.

### 3. Design And Risk Note

Before edits, write a short internal design note:

- Intended behavior.
- Files or modules likely to change.
- Invariants that must not break.
- Tests or checks that will prove the change.
- Documentation that may need synchronization.

For high-risk work, prefer adding or locating a failing test before changing
implementation.

### 4. Implementation

Implement in small reviewable slices:

- Reuse existing abstractions before adding new ones.
- Keep business rules out of thin entrypoint layers.
- Keep IO, runtime scheduler, persistence, and UI boundaries explicit.
- Avoid unrelated formatting or opportunistic refactors.
- Preserve backward compatibility unless the task explicitly changes it.

### 5. Verification

Use `docs/agent-verification-matrix.md`:

- Run targeted tests first when possible.
- Run broader tests after touching shared contracts.
- Run release-grade checks for dependency, packaging, compatibility, or release
  work.
- Identify manual regression scenarios for runtime behavior that unit tests
  cannot simulate.

Output: command, result, and remaining gap.

### 6. Documentation Sync

Update docs in the same change when behavior changes:

- User-facing command, GUI, permission, message, or config behavior.
- Public API behavior or stability claims.
- Platform compatibility or dependency policy.
- Data migration and rollback expectations.
- Release notes or changelog entries for user-visible changes.

### 7. Evidence And Handoff

Finish with a compact evidence bundle:

- Changed files and reason.
- Tests run and result.
- Manual checks needed or intentionally skipped.
- Residual risks.
- Follow-up work only when it is directly connected to the change.

Use `docs/agent-evidence-template.md` for larger changes.

## Risk Classes

### R0 Static

Docs, comments, formatting, or test-only changes with no runtime impact.

Expected verification: lint or no-op validation where applicable.

### R1 Local

Single module behavior with narrow tests and no persistence, runtime scheduler,
permissions, or public API impact.

Expected verification: targeted unit tests.

### R2 Shared

Service, manager, controller, command, GUI, config, localization, or integration
changes used by multiple flows.

Expected verification: targeted tests plus `mvn test` when feasible.

### R3 Runtime-Critical

Persistence, migration, economy, permissions, scheduler/threading, entity/world
access, public API, dependency shading, or release packaging.

Expected verification: targeted tests, `mvn verify`, and manual regression scope.

### R4 Release-Critical

Version bump, release artifact, platform support claim, compatibility matrix, or
security/supply-chain change.

Expected verification: `mvn clean verify package`, release checklist, artifact
inspection, and documented rollback notes.

## Agent Harness Expectations

Agents should optimize for reviewability:

- Name the risk class.
- Explain why each file is touched.
- Prefer deterministic commands over ad hoc manual inspection.
- Treat failing checks as first-class output, not noise.
- Leave durable evidence that another agent can pick up without reconstructing
  hidden reasoning.
