---
name: compatibility-update
description: Use when Codex needs to update or preserve compatibility across runtimes, platforms, APIs, dependencies, build tools, packaging, shaded artifacts, optional integrations, or version support claims. Trigger for Java, Minecraft/server, library, dependency, CI, release artifact, public API, or migration compatibility work. Keep project-specific version matrices and platform rules in the repository project profile, not in this skill.
---

# Compatibility Update

Use this workflow when a change may affect where the project runs, what it can
integrate with, or what promises the release makes.

## Workflow

1. Read `AGENTS.md`, `docs/agent-pipeline.md`,
   `docs/agent-project-profile.md`, and `docs/agent-verification-matrix.md`
   when present.
2. Identify the compatibility surface:
   - Language/runtime version
   - Server/platform version
   - Public API or binary contract
   - Dependency version or shaded package
   - Optional integration
   - Config/data migration
   - CI or release artifact
3. Write the before/after support claim.
4. Check whether the implementation, docs, and metadata all express the same
   claim.
5. Implement through adapters, reflection, feature detection, or migration paths
   when backward compatibility matters.
6. Add tests for old and new shapes where practical.
7. Run release-grade verification for dependency, packaging, public API, or
   platform support changes.
8. Update compatibility and release documentation.

## Guardrails

- Do not strengthen support claims beyond available evidence.
- Keep compile baselines stable unless the task explicitly raises them.
- Prefer compatibility adapters over direct references to newer platform APIs.
- Treat public API removals, data schema changes, and package relocation changes
  as high risk.
- Keep optional dependencies optional.

## Evidence Checklist

- What compatibility claim changed?
- Which metadata files changed?
- Which tests cover old behavior?
- Which tests cover new behavior?
- Which manual smoke tests remain?
- Is rollback or downgrade behavior documented?
