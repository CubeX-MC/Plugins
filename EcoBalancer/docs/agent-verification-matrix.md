# Agent Verification Matrix

Use this matrix to scale verification to risk. Prefer the smallest gate that
would catch the likely regression, then broaden when shared contracts or release
claims are involved.

## Change-Type Matrix

| Change type | Risk | Required context | Automated verification | Manual/runtime verification |
| :-- | :-- | :-- | :-- | :-- |
| Docs only | R0 | Affected doc and project profile | Spell/link review where practical | None |
| Pure model/util | R1 | Owning class and tests | Targeted test class | None unless behavior is user-visible |
| Service or manager logic | R2 | Owning service/manager, callers, tests | Targeted tests, then `mvn test` when shared | Relevant baseline scenario if runtime flow changes |
| Command behavior | R2 | Command class, command service, permissions, locale files | Command/service tests, language key test | Permission and failure-path smoke check |
| GUI behavior | R2 | View, controller, listener, permissions, locale files | GUI controller/listener tests | Confirm destructive actions and navigation path |
| Config read/default | R2/R3 | `ConfigFacade`, `config.yml`, updater tests, docs | Config updater/facade tests, `mvn test` | `/m reload` smoke check for release work |
| Data migration/persistence | R3 | Manager, updater, SaveCoordinator, old data shape | Persistence and migration tests, `mvn verify` | Backup/rollback scenario from release checklist |
| Train/boarding/runtime flow | R3 | Listener/service/train classes and regression baseline | Targeted tests plus `mvn test` or `mvn verify` | Scenario A-D as applicable |
| Portal or cross-world flow | R3 | PortalManager, train session, scheduler policy | Portal/train tests plus `mvn verify` | Scenario E on Paper/Folia if release-impacting |
| Scheduler/Folia work | R3/R4 | `SchedulerUtil`, lifecycle, architecture, compatibility | Targeted lifecycle/train/portal tests, `mvn verify` | Real Folia smoke test before stronger support claims |
| Economy/pricing | R3 | TicketService, PriceService, line services, locale files | Pricing/ticket/train tests, `mvn test` | Failure-path ride smoke if runtime-impacting |
| Public API | R3 | `MetroAPI`, docs/api.md, tests | API tests, `mvn test` | Consumer compatibility review |
| Dependency/shade/build | R3/R4 | `pom.xml`, dependency tree, compatibility docs | `mvn verify`, artifact inspection | Startup smoke test when shaded runtime changes |
| Release/version | R4 | Release checklist, changelog, compatibility, README | `mvn clean verify package` | Full release checklist |

## Test Selection Rules

- If one class owns the behavior, run its targeted test first.
- If a shared service, manager, or lifecycle class changed, run `mvn test`.
- If dependency, shading, coverage, SpotBugs, Folia support, persistence, public
  API, or release artifact is involved, run `mvn verify`.
- If the artifact itself matters, run `mvn clean verify package`.
- If unit tests cannot simulate the risk, document the manual regression
  scenario instead of pretending the risk is covered.

## Evidence Format

For every verification command, record:

- Command.
- Result.
- Relevant summary, such as number of tests or gate status.
- Any warnings that might matter for release quality.

Example:

```text
mvn verify
Result: passed. 524 tests, JaCoCo gate met, SpotBugs 0 issues.
Notes: shade produced overlap warnings already known for shaded dependencies.
```

## Manual Regression Mapping

- Boarding/departure/arrival/terminal: Scenario A and D.
- Multi-line candidate sorting and direction: Scenario B and C.
- GUI destructive operations: route protection and clear-route checks.
- Portals: Scenario E.
- Rail protection: Scenario F.
- Map integrations: provider-specific pass in `docs/regression-baseline.md`.
- Release: all pre-release, runtime validation, and packaging items in
  `docs/release-checklist.md`.

## Stop Conditions

Pause and report before proceeding when:

- A required verification command fails for reasons unrelated to the current
  change and the failure blocks confidence.
- The change requires a live server scenario that is not available locally.
- Existing uncommitted user changes overlap with the files needed for the task
  and the intended edit cannot be isolated safely.
- A compatibility or support claim would become stronger than the evidence.
