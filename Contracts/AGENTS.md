# Agent Entry Point

This file is the stable entry point for agents working in this repository. It
routes agents to the reusable pipeline, the project-specific profile, and the
verification gates that keep changes reviewable.

## First Reads

Read these in order before making non-trivial changes:

1. `docs/agent-pipeline.md` for the reusable agent workflow.
2. `docs/agent-project-profile.md` for this plugin's runtime, architecture,
   risk, and documentation rules.
3. `docs/agent-verification-matrix.md` to select tests and manual checks.
4. Task-specific docs when present:
   - Current plan or implementation notes: `PLAN.md`, `DESIGN.md`
   - Operator-facing behavior: `README.md`
   - Architecture, API, compatibility, regression, or release docs under
     `docs/`

## Skill Routing

Use reusable skills for process discipline, then load project facts from
`docs/agent-project-profile.md`.

- `skills/code-optimization/SKILL.md`: refactors, performance, reliability, or
  maintainability improvements.
- `skills/compatibility-update/SKILL.md`: Java, Minecraft, server platform,
  dependency, shading, or optional integration compatibility work.
- `skills/docs-maintenance/SKILL.md`: README, API, config, migration, release,
  or operator documentation updates.
- `skills/hci-design/SKILL.md`: command UX, GUI flows, permission feedback,
  in-game copy, onboarding, or human-facing workflow changes.

Do not put project-specific details into skills. Skills should remain portable;
project facts belong in `docs/agent-project-profile.md`.

## Working Rules

- Preserve existing behavior unless the task explicitly asks for a behavior
  change.
- Keep changes small, scoped, and reversible.
- Prefer existing service, manager, controller, lifecycle, and utility patterns.
- Do not bypass documented project boundaries in `docs/agent-project-profile.md`.
- Do not modify user-visible behavior without checking documentation and
  regression impact.
- Do not change config, data schema, permissions, public API, economy, storage,
  or runtime scheduling without updating the relevant docs and tests.
- Do not hardcode player-visible messages in Java when the project profile says
  localization is required.

## Verification Commands

Use the verification matrix to select the smallest sufficient gate:

- Narrow unit check: `mvn "-Dtest=ClassNameTest" test`
- Standard check: `mvn test`
- Release-grade check: `mvn verify`
- Artifact check: `mvn clean verify package`

Record what was run and what remains unverified using
`docs/agent-evidence-template.md`.

## Definition of Done

A change is complete when:

- The risk class is named and matched with appropriate verification.
- Automated tests pass for the affected surface, or any gap is explicit.
- Documentation is synchronized for user-visible, API, config, compatibility,
  migration, permission, storage, economy, or release-impacting changes.
- Manual regression scope is identified when runtime behavior cannot be fully
  covered by unit tests.
- The final response includes evidence, residual risk, and touched files at a
  level useful for review.
