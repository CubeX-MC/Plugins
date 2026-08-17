# Contract Agent Verification Matrix

Run commands from the monorepo root in Windows PowerShell. Start with the
smallest gate that can catch the likely regression, then broaden for shared or
runtime-critical behavior.

## Change-Type Matrix

| Change type | Risk | Automated verification | Manual/runtime verification |
| :-- | :-- | :-- | :-- |
| Docs/profile/artifact/harness only | R0 | Link/path/command consistency review; optional `:Contract:tasks` when task names changed | None |
| Pure model/util | R1 | Filtered `:Contract:test` | None unless player-visible |
| Service/query logic | R2 | Targeted tests, then `:Contract:test` | Relevant happy and blocked paths |
| Command behavior | R2 | Command/service tests, then `:Contract:test` | Allowed and denied permission paths |
| GUI/inventory flow | R2/R3 | GUI/query/service tests, then `:Contract:test` | Navigation, refresh, stale-click, cancellation, confirmation |
| Config/localization | R2/R3 | Migration/language tests, then `:Contract:test` | Reload and both locale paths |
| Storage/schema/migration | R3 | Persistence/migration tests, `:Contract:build`, `:Contract:jarGate` | Legacy data load, restart, backup, rollback |
| Economy/escrow/items | R3 | Service/recovery tests, `:Contract:build`, `:Contract:jarGate` | Vault success/failure, insufficient funds, inventory rollback |
| Scheduler/shutdown/restart | R3 | Lifecycle/storage tests, `:Contract:build`, `:Contract:jarGate` | Paper restart/reload and, when relevant, Folia smoke |
| Dependency/shading/artifact | R3/R4 | `:Contract:build`, `:Contract:jarGate` | Clean server startup and jar inspection beyond jarGate coverage |
| Optional plugin integration | R3 | Connector/adapter/degradation tests, provider tests, `:Contract:build`, `:Contract:jarGate` | Start with provider absent and present; provider reload/disable when practical |
| Version/platform/release claim | R4 | Clean `:Contract:build`, `:Contract:jarGate` | Install/start/reload smoke plus rollback notes |

## Active Artifact Matrix

| Artifact phase | Minimum automated gate | Required runtime scenarios |
| :-- | :-- | :-- |
| P1 stacked batch display and submitted/total progress | Batch summary/query tests, competing-accept service test, `:Contract:test`, `:Contract:build` | 32-child stack, accept decrement, stale final-slot click, 12/32 after completed/disputed children |
| P2 template library | Template model/store migration tests, permission tests, `:Contract:test`, `:Contract:build`, `:Contract:jarGate` | Save/load/edit/delete, restart persistence, foreign/server template denial, no money movement on load |
| P3 one-time scheduled publication | Status/storage/recovery/scheduler tests, `:Contract:build`, `:Contract:jarGate` | Vault escrow, offline owner, restart before/after due time, exactly-once opening, pre-publication cancellation/refund |

## Canonical Commands

```powershell
# Targeted test class
.\gradlew.bat :Contract:test --tests "org.cubexmc.contract.SomeTest"

# Full Contract tests
.\gradlew.bat :Contract:test

# Standard compile/test/shadow artifact gate
.\gradlew.bat :Contract:build

# Deployment artifact invariants
.\gradlew.bat :Contract:jarGate
```

For a clean release-grade rerun, use:

```powershell
.\gradlew.bat :Contract:clean :Contract:build :Contract:jarGate
```

## Evidence Format

For every command record:

- exact command;
- pass/fail;
- test count or artifact path when relevant;
- warnings that affect confidence;
- whether a retry was needed and why.

Do not describe a unit test as proving real Vault, Paper Dialog, Folia, item,
or restart behavior.

## Stop Conditions

Pause and report when:

- a required gate fails for an unrelated reason and blocks confidence;
- live runtime verification is required but unavailable;
- existing Contract changes overlap the intended slice and cannot be isolated;
- a migration has no safe compatibility or rollback story;
- a platform or artifact claim would exceed available evidence;
- the requested change contradicts the active artifact's frozen semantics.
