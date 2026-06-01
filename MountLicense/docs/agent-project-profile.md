# Agent Project Profile

This file contains MountLicense-specific facts for agents. Reusable skills
should read this file instead of embedding project-specific knowledge.

## Project Identity

- Project: MountLicense
- Type: Minecraft server plugin
- Language/runtime: Java 17, Maven, Spigot API compile baseline
- Main class: `org.cubexmc.mountlicense.MountLicensePlugin`
- Artifact: `target/mountlicense-<version>.jar`
- Core promise: register, protect, park, trust, locate, and recall mounts or
  physical vehicles as player-owned transport assets.

## Runtime Matrix

- Compile API: Spigot API 1.18.2
- Plugin API version: `1.18`
- Java build target: 17
- Server platforms claimed in docs: Spigot/Paper 1.18.2+
- Folia declaration: none in `plugin.yml`; do not strengthen Folia support
  claims without evidence.
- Optional dependencies: Vault for registration economy.
- Build packaging: Maven Shade Plugin creates the final jar; runtime
  dependencies are currently provided or test scoped.

Platform claims must stay synchronized across:

- `pom.xml`
- `src/main/resources/plugin.yml`
- `README.md`
- Release notes when present

## Architecture Map

- Bootstrap/lifecycle: `MountLicensePlugin`
- Config/profile defaults: `config/ConfigManager`,
  `config/ProfileRegistry`, `src/main/resources/config.yml`,
  `src/main/resources/vehicle-profiles.yml`
- Commands: `command/MountLicenseCommand`
- Listeners: `listener/RegistrationListener`, `ProtectionListener`,
  `AutoParkListener`, `KeyItemListener`
- Services: `RegistryService`, `OwnershipService`, `ParkingService`,
  `RecallService`, `ItemFactory`, `PdcKeys`
- Persistence: `persistence/VehicleIndex`, runtime `vehicles.yml`
- Economy: `integration/EconomyHook`
- Models: `model/VehicleRecord`, `VehicleState`, `VehicleProfile`,
  `VehicleFeature`
- Localization: `lang/LanguageManager`, `src/main/resources/lang/*.yml`

## Hard Boundaries

- Player-visible messages should go through language files and
  `LanguageManager`.
- PDC keys should stay centralized in `PdcKeys`.
- Command handling should validate input and delegate ownership, registry,
  parking, recall, and item behavior to services.
- Registration and protection changes must preserve owner/trustee/admin bypass
  semantics.
- Persistence changes must preserve `VehicleIndex` dirty tracking, autosave,
  reload, and disable flush expectations.
- Config changes require default resource updates and README synchronization.
- Bukkit entity, player, inventory, and teleport access should stay on safe
  server-thread event or scheduler paths.

## Data And Config Surfaces

Default resources:

- `config.yml`
- `vehicle-profiles.yml`
- `plugin.yml`
- `lang/zh_CN.yml`
- `lang/en_US.yml`

Runtime data:

- `plugins/MountLicense/vehicles.yml`
- PDC tags on registered entities and key/license items

Any config or data schema change requires:

- Default resource update.
- Compatibility read path or migration when old data exists.
- Tests where practical, or explicit manual regression scope.
- README update and operator-facing rollback notes when release-impacting.

## Human Interaction Surfaces

MountLicense UX happens through:

- `/mountlicense` and `/ml` commands.
- Sneak-right-click registration and key binding.
- Right-click key recall.
- Vehicle interaction protections for mount, damage, destroy, inventory, and
  leash actions.
- Localized chat messages, item display names, and lore.
- Permission failures and owner/trustee/admin boundaries.

Human-facing changes should preserve:

- Short ID behavior for player-facing vehicle references.
- Clear distinction between owner, trustee, bystander, and admin bypass rights.
- No misleading recall success when the vehicle is unsupported, unloaded, dead,
  or outside the search radius.
- Consistent permission semantics between commands and event listeners.
- Localized messages across all locale files.

## High-Risk Runtime Flows

Treat these as R3 unless proven narrower:

- Vehicle registration and PDC writes.
- Ownership, trustee, and admin bypass checks.
- Protection listeners for mount, damage, destroy, inventory, and leash.
- Park, unpark, lock, unlock, release, auto-park, and entity death cleanup.
- Key binding, key item metadata, recall, safe destination checks, and locate.
- Vehicle index persistence, autosave, reload, and disable flush.
- Vault charging or refund behavior.
- Vehicle profile changes that alter allowed entity features.

## Verification Defaults

Standard commands:

- Targeted unit test: `mvn "-Dtest=ClassNameTest" test`
- All unit tests or compile check: `mvn test`
- Quality/package gate: `mvn verify`
- Release artifact: `mvn clean verify package`

Current expected gate:

- `mvn verify` compiles, runs any present tests, and builds the shaded artifact.

Manual runtime checks:

- Use the README installation and phase verification sections for live server
  scenarios.
- Prioritize the affected command, permission, listener, PDC, persistence, and
  reload paths.

## Documentation Sync Map

- Command, permission, item, or interaction change: README, `plugin.yml`,
  locale files.
- Config or profile change: `config.yml`, `vehicle-profiles.yml`, config/profile
  managers, README.
- Persistence or PDC schema change: `VehicleIndex`, `PdcKeys`, migration notes,
  README when operator-visible.
- Platform/dependency change: `pom.xml`, `plugin.yml`, README, release notes.
- Economy change: `EconomyHook`, config defaults, README, failure-path docs.

## Evidence Expectations

Final handoff should state:

- Risk class.
- Files changed.
- Tests run.
- Manual regression scope.
- Documentation updated or intentionally unchanged.
- Residual risk, especially for real server behavior not covered by unit tests.
