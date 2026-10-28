# AGENTS.md — instructions for future engineering sessions

Read this file before editing anything in this repository.

## Source of truth

- `RebellionSMP_Codex_Master_Prompt.md` (on `develop`) is the authoritative product
  spec: architecture, goals, phases, and the quality bar. Re-read it fresh from
  the remote at the start of every session; it changes.
- `docs/MASTER_LEDGER.md` tracks what is planned vs. actually implemented.
  Update it after every vertical slice; never mark a feature "implemented"
  without a passing test.
- `docs/WORKLOG.md` is the chronological record. Append an entry per session
  with real commands and results. Do not rewrite old entries.

## Hard rules

1. **Branch policy.** All work lands on `develop`. Never commit features on
   `main`, never force-push, never rewrite published history. Focused feature
   branches off `develop` are welcome for large slices.
2. **Every push to `develop` auto-releases.** The `release` workflow
   (`.github/workflows/release.yml`, ADR-0010) builds, tests, and publishes
   the next numbered dev build as a GitHub **prerelease** (`vX.Y.Z-dev.N`,
   never `latest`). It also pushes a `release: … [skip ci]` version-bump
   commit to `develop` — pull before continuing your session. To land work
   without cutting a release, put `[skip ci]` in the head commit message;
   the next ordinary push releases it. Don't hand-edit `version=` in
   `gradle.properties` unless bumping the base version (`0.1.0-SNAPSHOT` →
   `0.2.0-SNAPSHOT`): the pipeline stamps the dev number itself.
3. **Commit identity.** `git config user.name "Abasing"` locally; keep the
   configured account email. Confirm with `git log -1 --format=fuller` before
   pushing. Never add model/agent co-authors.
4. **No land claims.** Any mechanic that denies block placement/breaking or
   container access by non-team members must not exist outside the two
   documented exceptions (prison structure, artifact origin recovery).
5. **No main-thread SQL**, no unbounded queues, no per-player threads, no
   world scans per tick. Database work goes through
   `DatabaseService`'s bounded executor; world/player mutation runs on the
   server scheduler.
6. **Fail closed for valuables.** If persistence is unavailable, unique assets
   (dragon lineages, artifacts, currency, unique item IDs) are never minted,
   moved, or consumed.
7. **One runtime identity for the API.** Feature modules `depend: [RebellionAPI]`
   and must never shade API classes.
8. **Every module owns its config.** `config.yml` + `messages.yml` per feature
   module; `database.yml` only in Core. DeluxeMenus-shaped `gui_menus` registry
   for menus.
9. **Closed-tag MiniMessage.** All message templates must have explicitly closed
   tags; the loader validates with strict-mode MiniMessage and rejects unclosed
   tags. Runtime placeholders (`<version>`, …) are exempt.

## Build and test

```bash
./gradlew build        # compiles all modules + runs tests
./gradlew test         # JUnit 5 suites only
./gradlew :rebellion-core:shadowJar   # shaded Core jar
```

- Toolchain: Java 25 (matches Paper 26.3 class files, major 69). Gradle 9.8.2
  wrapper is checked in; the daemon may run on Java 21.
- Dependency pins live in `gradle/libs.versions.toml`. Bump deliberately and
  re-verify compatibility against Paper 26.3 before changing them.
- Tests are plain JUnit 5 + real SQLite files (no Bukkit mocking of server
  statics). Core services take injected dependencies; keep it that way so the
  suite stays honest.
- Boot-level Paper verification runs headlessly: `./scripts/boot-smoke.sh`
  (ADR-0011; fetch → jars → start/send/stop). Run it after any
  plugin-wiring, descriptor, packaging, or config-boot change. It is the
  gate that caught the `YamlConfiguration.load(String)` path-vs-content
  defect class — unit tests cannot see wiring bugs.

## Code style

- Namespace `net.zaminmc.rebellion.*`; module subpackages per the master prompt.
- Java, not Kotlin. Records for DTOs. No `Player`/`World`/entity references in
  caches, DB models, or map keys — UUIDs only.
- Event handlers stay thin; state transitions live in testable services.
- Comments explain invariants and non-obvious behavior, not mechanics.
- A TODO must name a concrete follow-up; never leave a TODO where acceptance
  criteria demand behavior.

## Before you commit

`./gradlew build` green, `git status` reviewed, no generated files
(`build/`, `run/`, worlds, sqlite DBs, secrets), docs updated
(MASTER_LEDGER / WORKLOG / affected ADRs), author identity confirmed.
