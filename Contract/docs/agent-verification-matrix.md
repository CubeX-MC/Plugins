# Agent Verification Matrix

Use this matrix to scale verification to risk. Prefer the smallest gate that
would catch the likely regression, then broaden when shared contracts or release
claims are involved.

## Change-Type Matrix

| Change type | Risk | Required context | Automated verification | Manual/runtime verification |
| :-- | :-- | :-- | :-- | :-- |
| Docs only | R0 | Affected doc and project profile | Spell/link review where practical | None |
| Pure model/util | R1 | Owning class and tests | Targeted test class when present | None unless behavior is user-visible |
| Service or manager logic | R2 | Owning service/manager, callers, tests | Targeted tests, then `mvn test` when shared | Relevant runtime smoke if behavior reaches players |
| Command behavior | R2 | Command class, permissions, locale files, README | Command/service tests, `mvn test` when available | Permission and failure-path smoke check |
| GUI or inventory flow | R2/R3 | GUI/listener classes, commands, permissions, locale files | GUI/listener/service tests, `mvn test` | Navigation, confirmation, and blocked-action smoke check |
| Config or localization | R2/R3 | Config manager, `config.yml`, `plugin.yml`, locale files, README | Config/language tests when present, `mvn test` | Reload smoke check for release work |
| Data storage, schema, or migration | R3 | Storage/repository classes, old data shape, defaults, docs | Persistence and migration tests, `mvn verify` | Backup, downgrade, and reload scenario |
| Economy or escrow | R3 | Economy adapter, service state machine, permissions, config | Economy/service tests, `mvn test` or `mvn verify` | Vault provider smoke check and failure-path review |
| Entity, item, PDC, or Bukkit event flow | R3 | Listener/service classes, PDC keys, config, locale files | Targeted tests where possible, `mvn test` | Live server smoke for affected event path |
| Scheduler, threading, or shutdown behavior | R3/R4 | Plugin lifecycle, scheduled tasks, storage flush paths, platform docs | Targeted lifecycle/storage tests, `mvn verify` | Real server stop/reload smoke before stronger claims |
| Public API or integration contract | R3 | API/integration classes, docs, compatibility notes | API tests, `mvn test` | Consumer compatibility review |
| Dependency, shade, or build metadata | R3/R4 | `pom.xml`, dependency tree, `plugin.yml`, README | `mvn verify`, artifact inspection | Startup smoke when runtime classpath changes |
| Release/version/support claim | R4 | Release notes, README, compatibility docs, build metadata | `mvn clean verify package` | Full install/start/reload smoke check |

## Test Selection Rules

- If one class owns the behavior, run its targeted test first when the test
  exists.
- If a shared service, manager, storage, listener, command, or lifecycle class
  changed, run `mvn test`.
- If dependency, shading, persistence, public API, economy, scheduler, or release
  artifact behavior is involved, run `mvn verify`.
- If the artifact itself matters, run `mvn clean verify package`.
- If unit tests cannot simulate the risk, document the manual regression
  scenario instead of pretending the risk is covered.

## Evidence Format

For every verification command, record:

- Command.
- Result.
- Relevant summary, such as test count, package output, or gate status.
- Any warnings that might matter for release quality.

Example:

```text
mvn verify
Result: passed. 42 tests, package built.
Notes: shade produced expected dependency overlap warnings.
```

## Manual Regression Mapping

- Command or permission changes: run the happy path plus at least one denied
  path for the affected command.
- GUI or inventory changes: check navigation, item clicks, cancellation, and
  destructive-action confirmation when applicable.
- Config or localization changes: reload and confirm the changed setting or
  message appears in-game.
- Storage changes: create, reload, restart, and confirm data survives; include a
  rollback note for release-impacting changes.
- Economy changes: test success, insufficient funds, missing provider, and
  rollback or refund path.
- Entity/item/PDC changes: exercise the actual Bukkit event path on a server.
- Release work: install the built jar on a clean server profile and confirm
  startup, command registration, and default resource generation.

## Stop Conditions

Pause and report before proceeding when:

- A required verification command fails for reasons unrelated to the current
  change and the failure blocks confidence.
- The change requires a live server scenario that is not available locally.
- Existing uncommitted user changes overlap with the files needed for the task
  and the intended edit cannot be isolated safely.
- A compatibility or support claim would become stronger than the evidence.
