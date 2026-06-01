# Agent Project Profile

This file contains BookLite-specific facts for agents. Reusable skills should
read this file instead of embedding project-specific knowledge.

## Project Identity

- Project: BookLite
- Type: Minecraft server plugin
- Language/runtime: Java 17, Maven, Spigot API compile baseline
- Main class: `org.cubexmc.booklite.BookLitePlugin`
- Artifact: `target/booklite-<version>.jar`
- Core promise: store written-book content in SQLite while keeping player items
  lightweight, deduplicated, restorable, and admin-reviewable.

## Runtime Matrix

- Compile API: Spigot API 1.18.2
- Plugin API version: `1.18`
- Java build target: 17
- Server platforms claimed in docs: Paper/Spigot
- Folia declaration: none in `plugin.yml`; do not strengthen Folia support
  claims without evidence.
- Required runtime dependencies: none declared as plugin dependencies.
- Bundled dependencies: SQLite JDBC, SLF4J NOP, and Gson; Gson is relocated to
  `org.cubexmc.booklite.libs.gson`.

Platform and dependency claims must stay synchronized across:

- `pom.xml`
- `src/main/resources/plugin.yml`
- `README.md`
- Release notes when present

## Architecture Map

- Bootstrap/lifecycle: `BookLitePlugin`
- Config defaults: `config/ConfigManager`, `src/main/resources/config.yml`
- Commands: `command/BookLiteCommand`
- Listeners: `listener/BookListener`
- Services: `BookService`, `BookRestorer`, `BookCodec`, `BookCache`, `PdcKeys`
- Storage: `storage/BookRepository`, runtime SQLite database from
  `storage.sqlite_file`
- Models: `model/BookRecord`
- Localization: `lang/LanguageManager`, `src/main/resources/lang/*.yml`

## Hard Boundaries

- Player-visible messages should go through language files and
  `LanguageManager`.
- PDC keys should stay centralized in `PdcKeys`.
- Book serialization and item shell rules should stay in `BookCodec` and
  `BookRestorer`.
- Storage reads/writes should go through `BookRepository`; do not duplicate SQL
  or schema rules in command/listener code.
- Command and listener classes should validate player intent and delegate book
  conversion, restoration, cache, and storage work to services.
- Config changes require default resource updates and README synchronization.
- SQLite, cache, and Bukkit inventory/book metadata changes must preserve reload
  and plugin-disable behavior.

## Data And Config Surfaces

Default resources:

- `config.yml`
- `plugin.yml`
- `lang/zh_CN.yml`
- `lang/en_US.yml`

Runtime data:

- `plugins/BookLite/books.db` by default, configurable with
  `storage.sqlite_file`
- PDC tags on BookLite shell items

Any config, schema, or PDC shape change requires:

- Default resource update.
- Compatibility read path or migration when old books exist.
- Tests where practical, or explicit manual regression scope.
- README update and operator-facing rollback notes when release-impacting.

## Human Interaction Surfaces

BookLite UX happens through:

- `/booklite` and `/bl` commands.
- Signed book conversion and right-click reading.
- Crafting copy behavior.
- Lectern read compatibility.
- Admin list, info, read, soft delete, undelete, purge, status, and restore
  commands.
- Uninstall mode passive restoration on player join and inventory open.
- Localized messages, plus item title/author behavior for visually transparent
  BookLite shell items.

Human-facing changes should preserve:

- Clear distinction between original vanilla books, BookLite shells, deleted
  records, and missing records.
- No accidental content leak for soft-deleted books.
- Copy generation limits and vanilla-like restoration behavior.
- Admin destructive-action clarity for delete, undelete, and purge.
- Localized messages across all locale files.

## High-Risk Runtime Flows

Treat these as R3 unless proven narrower:

- SQLite schema, connection, WAL, and purge behavior.
- Book conversion, restoration, signing, and crafting copy generation.
- PDC schema for shell items.
- Cache invalidation and reload behavior.
- Soft delete, undelete, purge, and admin reads.
- Uninstall mode passive restoration.
- Lectern compatibility and inventory event handling.
- Dependency shading or SQLite driver packaging.

## Verification Defaults

Standard commands:

- Targeted unit test: `mvn "-Dtest=ClassNameTest" test`
- All unit tests or compile check: `mvn test`
- Quality/package gate: `mvn verify`
- Release artifact: `mvn clean verify package`

Current expected gate:

- `mvn verify` compiles, runs any present tests, and builds the shaded artifact.

Manual runtime checks:

- Use README command and uninstall-mode flows for live server scenarios.
- Prioritize conversion, read, restore, admin review, storage reload, and
  soft-delete/purge paths.

## Documentation Sync Map

- Command, permission, or admin workflow change: README, `plugin.yml`, locale
  files.
- Config change: `config.yml`, `ConfigManager`, README.
- Storage or schema change: `BookRepository`, migration notes, README when
  operator-visible.
- PDC/item format change: `PdcKeys`, `BookCodec`, `BookRestorer`, README.
- Platform/dependency change: `pom.xml`, `plugin.yml`, README, release notes.

## Evidence Expectations

Final handoff should state:

- Risk class.
- Files changed.
- Tests run.
- Manual regression scope.
- Documentation updated or intentionally unchanged.
- Residual risk, especially for real server behavior not covered by unit tests.
