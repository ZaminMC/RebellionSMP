# ADR-0011 — Headless Paper 26.3 boot-smoke harness as a standing verification gate

Date: 2026-10-28 · Status: accepted

## Context

Every session before this one recorded "no live Paper 26.3 server in this
environment" and deferred all server-level verification to a hypothetical
manual pass. Meanwhile the release pipeline (ADR-0010) publishes installable
JARs for every push — artifacts whose boot behavior had never been observed
on a real server. The environment, however, satisfies every requirement for
a *headless* server: Java 25, ~2 GB free RAM, ~8 GB disk, and network access
to the PaperMC fill API. Nothing about boot verification actually needs an
interactive client; only player-level flows do.

So this session built the harness and ran it — and the very first boot
caught five real defects that all 289 unit tests were structurally unable
to catch:

1. **`YamlConfiguration.load(String)` resolves a path, not content**
   (Teams, Wards). The call `yaml.load(Files.readString(config))` produced
   `FileNotFoundException` whose "path" was the entire file content. The
   String overload is the file-path variant; content parsing is
   `loadFromString`. Unit tests never hit this because they construct
   `YamlConfiguration` themselves — the defect lived only in the plugin
   wiring layer.
2. **Items never extracted its bundled `menus/*.yml`** into the data
   folder, so first boot failed with "file not found" for both bundled
   menus. MenuEngineTest passed because the test itself copied the bundled
   files into its temp dir — doing the extraction the plugin forgot.
3. **`onDisable` rewrote FAILED modules to `DISABLED — "shutdown"`**,
   erasing the failure reason from `/rebellion modules` and making a failed
   enable indistinguishable from a clean stop.
4. **A failed Items bootstrap never registered with the module registry**,
   so items vanished from `/rebellion modules` entirely instead of showing
   FAILED (Teams/Wards did register; Items was inconsistent).
5. **A paired `</state>` closing tag leaked as literal text** in the
   `rebellion status` backend line: MiniMessage placeholder resolvers
   consume the opening `<state>` only; the template's `</state>` degraded
   to text ("… SQLITE (healthy)</state>"). Placeholder tags have no closing
   form — same rule as `<version>`.

## Decision

1. **`scripts/boot-smoke.sh` is a standing gate in the repo.** It fetches
   the pinned Paper 26.3 build (sha256-verified via the fill v3 API — the
   legacy v2 download API is sunset), stages the built installable JARs,
   boots a flat offline-mode world headlessly (FIFO console), and provides
   `send`/`stop` console plumbing. Anyone (engineer or Codex session) can
   reproduce the verification; the procedure and results live in
   TEST_PLAN.
2. **Run the smoke after any plugin-wiring, descriptor, packaging, or
   config-boot change**, and at minimum once per released dev build line.
   It verifies: install order, first-boot config generation, migrations,
   console surface, restart persistence, graceful shutdown.
3. **The defect classes above are now codified**: `loadFromString` for
   content (the plugins carry the comment), bundled resources are
   extracted copy-if-missing with traversal guards, FAILED module health
   is terminal until re-enable, and MiniMessage placeholder tags are bare.
4. **Honest scope**: boot-verified ≠ verified-on-Paper-26.3 in full.
   Player-level flows (menus clicking, team invites, ward walking, combat,
   the ADR-0008 dragon experiments) still require interactive verification
   and remain manual gates in TEST_PLAN.

## Consequences

- The five defects are fixed and the fixed jars re-verified live: all five
  modules enable in order, migrations apply and are restart-idempotent,
  console status/modules/diagnose render correctly, shutdown is clean.
- The harness found in one run what weeks of unit testing could not:
  wiring-layer defects are invisible to pure unit suites. This materially
  changes the evidence standard — "enables on real Paper" is now a testable
  claim, not a hope.
- First boot of a fresh server takes ~13 s on the flat world; restarts
  ~5 s. The harness is cheap enough to run every session.
- The scratch server directory (default `build/paper-smoke`) stays outside
  version control (worlds and server data must never be committed); the
  paper jar download (~53 MB) is cached per SERVER_DIR.
- Remaining honest gap recorded in MASTER_LEDGER: feature modules bundle
  `messages.yml` but do not yet extract/render it (hard-coded copy is still
  in use) — surfaced by this session, scheduled as a follow-up slice.
