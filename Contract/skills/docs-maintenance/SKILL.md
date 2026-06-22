---
name: docs-maintenance
description: Use when Codex needs to create, update, reconcile, or audit project documentation after code, API, config, compatibility, release, migration, command, permission, or user-facing behavior changes. Trigger for README updates, API docs, architecture docs, release notes, changelogs, operator guidance, agent docs, verification evidence, or documentation drift checks. Keep project-specific facts in the repository project profile and source docs, not in this skill.
---

# Docs Maintenance

Use this workflow to keep documentation synchronized with implementation.

## Workflow

1. Read `AGENTS.md`, `docs/agent-pipeline.md`, and
   `docs/agent-project-profile.md` when present.
2. Identify the documentation surface:
   - User instructions
   - Operator or admin guide
   - Config/defaults
   - Permissions or commands
   - Public API
   - Architecture/runtime policy
   - Compatibility matrix
   - Migration or release notes
   - Agent workflow docs
3. Compare docs with source-of-truth files.
4. Update the smallest set of docs that prevents drift.
5. Keep examples executable or clearly marked as illustrative.
6. Preserve user-facing language style already present in the repository.
7. Record what was updated and why.

## Drift Checks

- Version numbers match build metadata.
- Artifact names match package output.
- Commands match registered commands.
- Permissions match plugin or app metadata.
- Config paths match code reads and defaults.
- API docs match method names, return types, mutability, and lifecycle.
- Compatibility claims match build configuration and tested runtime matrix.
- Release notes mention migrations, breaking changes, and rollback guidance.

## Writing Rules

- Prefer concise operational guidance over broad explanation.
- Keep project-specific details in project docs, not reusable skills.
- Update translated or parallel docs together when the repository maintains
  them.
- For generated evidence, state commands and results exactly enough to audit.
