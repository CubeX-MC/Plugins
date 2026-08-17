# Contract Agent Entry Point

This is the stable Claude Code and Codex entry point for work scoped to the
Contract plugin. Repository-wide rules in `../AGENTS.md` remain authoritative;
this file adds Contract-specific routing and harness requirements.

## Reference Implementation Status

Contract is the reference adopter of the CubeX shared modules. When another
plugin's usage disagrees with Contract's, Contract is the pattern to copy, and a
gap in the shared modules is a bug to fix in `modules/` rather than to work
around in the plugin. What that means concretely:

| Module | What Contract uses it for |
| :-- | :-- |
| `cubex-core` | `CubexPlugin` lifecycle, `bind()` for every store and the GUI via `Terminable`, `Reloadable` for reload stages, `log()`, `messager()`, `text()` |
| `cubex-config` | `MigrationPlan`/`MigrationRunner` for config and lang schema, `ReloadChain` for `/contract admin reload`, `ResourceFiles` |
| `cubex-i18n` | One `I18nService` for **every** locale section |
| `cubex-scheduler` | `CubexScheduler` for all repeating and entity-bound tasks |
| `cubex-integrations` | Provider-classloader discovery for optional services; Contract also publishes its provider-owned escrow API |

Rules that follow from that status:

- **No plugin-local copy of a shared helper.** Contract has no `Text` util; it
  uses `CubexText` through `plugin.text()`. If a helper is missing, add it to the
  module.
- **Kotlin sources live in `src/main/kotlin`.** Only the vendored bStats
  `Metrics.java` stays under `src/main/java`.
- **Stores implement `Reloadable` + `Terminable`**, so `bind(store)` handles
  shutdown flushing and the store can be a named reload stage directly.
- **Reload is a `ReloadChain`, not a method of sequential calls.** Stages that
  must not run after an earlier failure use `addIf` with a gate; the returned
  `ReloadReport` names the stage that broke.
- **All logging goes through `CubexLogger`**, never `java.util.logging` directly.
  Classes that hold the plugin use `plugin.log()`; classes constructed with a
  file (the stores) take a `CubexLogger` parameter.
- **Commands register via `registerCommand(name, executor)`**, which also wires
  the tab completer and logs loudly when `plugin.yml` is missing the entry.

Not yet true of Contract, and therefore not yet the pattern — do not copy these
from it:

- bStats `Metrics.java` is still a per-plugin vendored copy (9 in the repo).
- There is no shared config-binding layer; Contract reads `config.getInt(...)`
  with inline defaults at each call site.

## First Reads

Read these in order before non-trivial Contract work:

1. `../AGENTS.md` for monorepo commands, commit scope, branch safety, and shared
   plugin constraints.
2. `docs/agent-pipeline.md` for the reusable workflow.
3. `docs/agent-project-profile.md` for current Contract build, runtime,
   architecture, data, and risk facts.
4. `docs/agent-verification-matrix.md` for the smallest sufficient test gate.
5. Task-specific artifacts and product docs:
   - Active batch/template/scheduling work:
     `docs/batch-template-scheduling-artifact.md`
   - Cross-feature roadmap and open work: `../PLAN.md` (repo-wide, single plan
     file; Contract items live in its per-plugin section)
   - Existing architecture and implementation history: `DESIGN.md`,
     `CHANGELOG.md`
   - Current operator-facing behavior: `README.md`

When an active artifact conflicts with an older plan for the same feature, the
active artifact controls that feature. Source code and build metadata control
facts about behavior that is already implemented.

## Skill Routing

- `skills/code-optimization/SKILL.md`: refactors, reliability, architecture, or
  maintainability work.
- `skills/compatibility-update/SKILL.md`: Java, Paper/Folia, dependency,
  shading, artifact, or migration compatibility.
- `skills/docs-maintenance/SKILL.md`: README, config, migration, release,
  profile, artifact, or harness documentation.
- `skills/hci-design/SKILL.md`: command UX, GUI flows, permissions, copy,
  confirmation, or player/operator workflow changes.

Keep portable process guidance in skills and Contract facts in the profile or
task artifact.

## Working Rules

- Preserve existing behavior unless the active artifact explicitly changes it.
- Keep implementation slices small, independently buildable, and reversible.
- Do not mix stacking, templates, scheduling, unrelated refactors, or new
  contract types in one commit.
- Keep state/economy rules in services, persistence in stores, and rendering in
  GUI classes.
- Do not hardcode player-visible messages **or colours**. Every GUI label, lore
  line, chat prompt and `ServiceResult.fail` reason resolves through
  `lang().ui(key, ...)` (or `lang().message(...)` for chat) and must be added to
  **both** `lang/zh_CN.yml` and `lang/en_US.yml` with the same `<placeholder>`
  names. `LanguageParityTest` fails the build on a missing key, a key set
  mismatch, or a placeholder mismatch. Pure classes that cannot reach `lang()`
  return a key plus placeholders (see `DraftProblem`) and let the caller render.
- Language values are **MiniMessage** (`<#RRGGBB>`, `<placeholder>`), not legacy
  `&#RRGGBB`. The i18n service renders them; `GuiItems` does no colour
  translation, so a `&`-code written in Kotlin will reach the player literally.
- Adding language keys means bumping `lang-version` and adding a migration step
  so existing servers get them; `LangV2ToV3Step` is the model — it converts
  operator-edited values and merges in whatever the bundle defines and the
  server's file lacks, without overwriting the operator's wording.
- Do not change config, permissions, schema, economy, scheduling, or public
  behavior without the mapped tests and documentation.
- Inspect the current branch and `git status --short -- Contract` before edits.
  Preserve parallel WIP and stage only Contract-scoped paths.
- Do not push `main` or another shared branch without explicit user approval.

## Verification Commands

Run from the monorepo root in PowerShell:

```powershell
# One test class
.\gradlew.bat :Contract:test --tests "org.cubexmc.contract.SomeTest"

# All Contract tests
.\gradlew.bat :Contract:test

# Compile, test, and build the deployable shadow jar
.\gradlew.bat :Contract:build

# Inspect deployable jar invariants
.\gradlew.bat :Contract:jarGate
```

Do not use Maven commands for this subproject. Record commands and results in
the active artifact and/or `docs/agent-evidence-template.md`.

## Active Handoff

The approved player request and executable implementation sequence for stacked
batch display, submitted/total progress, reusable templates, and optional
one-time scheduled publication are in:

`docs/batch-template-scheduling-artifact.md`

Phases P1-P3 are implemented on `codex/contract-batch-templates-scheduling`.
The next handoff is Paper runtime verification using the matrix scenarios for
concurrent accept-one, template permission boundaries, scheduled escrow,
restart activation, and pre-publication cancellation. Recurring publication,
automatic replenishment, bulk settlement, and a broad operator console remain
out of scope.

## Definition Of Done

A Contract change is done when:

- the artifact phase and risk class are named;
- targeted tests pass, followed by the required broader gate;
- data/economy/restart and manual-runtime gaps are explicit;
- README, config, permissions, locales, profile, and artifact are synchronized
  where the verification matrix requires them;
- the artifact records completed acceptance criteria and the next handoff;
- only intended Contract files are staged or committed.
