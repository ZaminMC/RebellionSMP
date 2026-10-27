# ADR-0010 — Release pipeline: numbered dev prereleases auto-cut from develop

Date: 2026-10-28 · Status: accepted

## Context

The repository had CI (`ci.yml`) that builds and tests on every push/PR and
uploads JARs as workflow artifacts, but no release path at all: tags: 0,
releases: 0, and no user-visible download. Master prompt §17 requires CI
artifacts "for inspection" and warns against publishing releases "without
explicit configuration and secrets"; §16 reserves `main` for reviewed,
release-ready work. The project owner then explicitly requested the
ZaminMC/ZaminTorch model: **every push to `develop` automatically becomes a
downloadable numbered build, published as a pre-release — never `latest`**.

ZaminTorch's `release.yml` (studied in full, 2026-10-28) provides the pattern:
tag-ledger numbering, pre-build version bump synced back with a `[skip ci]`
loop-breaker, lightweight tags, `gh release create --prerelease`, all writes
authored as Abasing through a PAT secret, with orphan-tag repair and
idempotent re-runs.

## Decision

1. **New workflow `.github/workflows/release.yml` owns push-to-develop.**
   `ci.yml` keeps pull requests (develop/main) and read-only push validation
   of `main`. Both run the same `./gradlew build`, so a develop push is never
   built twice. §17 stays satisfied: PRs, develop pushes, and main all get
   CI; the develop CI is the release pipeline itself.
2. **Numbering scheme `vX.Y.Z-dev.N`.** The base (`0.1.0`) is derived from
   `gradle.properties` (`version=0.1.0-SNAPSHOT` → `0.1.0`; post-bump
   `0.1.0-dev.N` strips to the same base). N comes from a single tag ledger
   over `v*.*.*-dev.N`, so a future base bump continues the numbering
   (`0.1.0-dev.7` → `0.2.0-dev.8`), mirroring ZaminTorch's dual-ledger
   behavior in a generalized form.
3. **Version stamping is one line** — `version=` in `gradle.properties` —
   because the version is centralized there and every module's
   `processResources` expands it into `plugin.yml`. The bump commit
   (`release: vX.Y.Z-dev.N — version bump [skip ci]`, authored as Abasing)
   is pushed back to develop **before** building, so JARs are built from the
   exact tree that gets tagged; the `[skip ci]` trailer prevents the
   pipeline from retriggering itself.
4. **Artifacts are discovered, not hardcoded**: exactly the modules that
   ship `src/main/resources/plugin.yml` are staged into `build/release/`
   (the §17 packaging layout), each verified to contain its descriptor and
   to be the one installable jar for the stamped version (`-thin` jars
   cannot match the glob). `rebellion-dragons` ships no descriptor yet
   (persistence layer only, ADR-0009) and is reported in the release notes
   as built-but-not-installable; it joins automatically when it gains
   `plugin.yml`. Future modules (rituals, prison, …) need zero pipeline
   edits.
5. **`gh release create --prerelease --verify-tag`** through
   `secrets.ABASING_TOKEN` (a classic PAT of the Abasing account, set as a
   repo secret), so the release, tag, and bump commit are authored as
   **Abasing**, never `github-actions[bot]`. The publish step asserts
   `isPrerelease == true` and fails otherwise. Prereleases are excluded
   from GitHub's "latest" pointer by design — that satisfies the
   never-latest policy without extra API calls.
6. **Failure semantics copied from ZaminTorch**: build/test failure → no
   tag, no release, number reused next run; orphan tag (tag pushed, release
   missing) → repair run resumes the same number; already-published HEAD →
   no-op; `[skip ci]` head commits postpone their push's release; releases
   queue in a `release-develop` concurrency group and are never cancelled
   mid-flight. One build retry for fresh-runner load flakiness — a second
   failure still fails the job; tests are never skipped.

## Consequences

- Every push to `develop` now yields a visible, downloadable dev build
  (`RebellionAPI/Core/Items/Teams/Wards` jars, install order documented in
  the notes), while `latest` stays reserved for the explicit reviewed
  develop → `main` release process.
- `develop`'s HEAD is usually a pipeline bump commit; history stays
  append-only (no rewrites), and each release tag points at the tree whose
  `gradle.properties` names that exact version.
- Release numbering is monotonic per repository, not per base version —
  `0.2.0-dev.N` continues from `0.1.0-dev.(N-1)` by design.
- The workflow needs the `ABASING_TOKEN` secret; without it the run fails at
  checkout with a clear auth error (the secret was created 2026-10-28).
  Rotation of that PAT requires updating the secret in one place.
- Publishing to `main` (stable, `latest`) remains a deliberate manual
  process outside this pipeline; automating it is a separate future ADR if
  ever requested.
- Marketplace publishing (Modrinth/Hangar) is out of scope; the pipeline
  only produces GitHub prereleases.
