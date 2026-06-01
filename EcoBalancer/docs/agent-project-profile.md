# Agent Project Profile

This file contains Metro-specific facts for agents. Reusable skills should read
this file instead of embedding project-specific knowledge.

## Project Identity

- Project: Metro
- Type: Minecraft server plugin
- Language/runtime: Java 17, Maven, Spigot API compile baseline
- Main class: `org.cubexmc.metro.Metro`
- Artifact: `target/metro-<version>.jar`
- Core promise: manage metro lines, stops, ride flow, GUI administration,
  portals, pricing, ownership, map integration, and public plugin API.

## Runtime Matrix

- Compile API: Spigot API 1.18.2
- Plugin API version: `1.18`
- Java build target: 17
- Server platforms: Spigot, Paper, Folia
- Folia declaration: `folia-supported: true`
- Optional dependencies: Vault, BlueMap, dynmap, squaremap, ViaVersion

Platform claims must stay synchronized across:

- `pom.xml`
- `src/main/resources/plugin.yml`
- `docs/compatibility.md`
- README files
- Release notes

## Architecture Map

- Bootstrap/lifecycle: `Metro`, `lifecycle/*`
- Config: `ConfigFacade`, `src/main/resources/config.yml`
- Commands: `command/newcmd/*`
- Command services: `service/*CommandService`
- GUI views/controllers: `gui/view/*`, `gui/controller/*`, `GuiManager`,
  `GuiListener`
- Core managers: `LineManager`, `StopManager`, `PortalManager`,
  `RailProtectionManager`, `RouteRecorder`
- Train runtime: `train/*`
- Persistence: `SaveCoordinator`, YAML data files, data updaters
- Public API: `api/MetroAPI`
- Map integrations: `integration/*`, `MapIntegrationLifecycle`
- Localization: `LanguageManager`, `src/main/resources/lang/*.yml`

## Hard Boundaries

- Config reads should go through `ConfigFacade` unless the setting is truly local
  and private.
- Player-visible messages should go through language files and language manager.
- Command classes should route, validate input, and delegate business work to
  services.
- GUI views render; GUI controllers handle navigation and click intent; business
  rules stay in services or managers.
- Bukkit world, block, player, entity, inventory, and minecart access must follow
  `docs/architecture.md` Scheduler Policy.
- Async code may perform file IO or work on already-built snapshots, but must not
  access unsafe Bukkit state.
- Persistence changes must preserve snapshot save semantics, dirty tracking,
  migration compatibility, and shutdown/reload flush expectations.
- Public API additions should prefer immutable snapshots for read-only external
  integrations.

## Data And Config Surfaces

Default resources:

- `config.yml`
- `lines.yml`
- `stops.yml`
- `plugin.yml`
- `lang/*.yml`

Runtime data:

- `lines.yml`
- `stops.yml`
- `portals.yml`
- Migration backups: `*.bak-<schema_version>`

Any config or data schema change requires:

- Default resource update.
- Compatibility read path or migration.
- Tests for old and new shapes.
- Documentation update.
- Operator-facing rollback or migration notes when release-impacting.

## Human Interaction Surfaces

Metro UX happens through:

- Commands and tab completion.
- Inventory GUI screens.
- Titles, action bars, sounds, lore, and chat messages.
- Permission failures and ownership/admin boundaries.
- Map markers from optional integrations.

Human-facing changes should preserve:

- Clear line IDs when display names are ambiguous.
- No misleading boarding prompt at terminal or unboardable stops.
- Consistent permission semantics between command and GUI actions.
- Localized messages across all locale files.
- Confirmation before destructive GUI operations.

## High-Risk Runtime Flows

Treat these as R3 unless proven narrower:

- Boarding: rail click, candidate line selection, minecart spawn, passenger add.
- Ride lifecycle: waiting, departure, arrival, terminal cleanup, manual exit.
- Economy: fare estimate, ticket check, charge, refund or failure path.
- Portal teleport: paired portals, world/chunk availability, passenger restore.
- Persistence: autosave, reload, disable flush, failed save, data migration.
- Folia/threading: entity scheduler, region scheduler, shutdown cleanup.
- Rail protection and route recording.
- Map provider activation and refresh.
- Public API mutations and snapshot contracts.

## Verification Defaults

Standard commands:

- Targeted unit test: `mvn "-Dtest=ClassNameTest" test`
- All unit tests: `mvn test`
- Quality gate: `mvn verify`
- Release artifact: `mvn clean verify package`

Current expected gate:

- `mvn verify` runs unit tests, JaCoCo coverage check, shade packaging, and
  SpotBugs.

Manual runtime checks:

- Use `docs/regression-baseline.md` for scenarios A-F.
- Use `docs/release-checklist.md` for release work.

## Documentation Sync Map

- Command, permission, or GUI change: README, README_en, `plugin.yml`, locale
  files, regression checklist if runtime behavior changes.
- Config change: `config.yml`, `ConfigFacade`, updater/migration path,
  compatibility docs if support claims change.
- API change: `docs/api.md`, API tests, compatibility notes when stability
  changes.
- Platform/dependency change: `pom.xml`, `plugin.yml`, `docs/compatibility.md`,
  release notes.
- Runtime-critical behavior: `docs/architecture.md` or
  `docs/regression-baseline.md` when assumptions or smoke tests change.

## Evidence Expectations

Final handoff should state:

- Risk class.
- Files changed.
- Tests run.
- Manual regression scope.
- Documentation updated or intentionally unchanged.
- Residual risk, especially for real server behavior not covered by unit tests.
