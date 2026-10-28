# ADR-0012 — Shared module message catalog (MessageCatalog in RebellionAPI)

Date: 2026-10-28 · Status: accepted

## Context

The master prompt (§8) requires every module's `messages.yml` to own "all
player-facing feedback, errors, warnings, usage text, state transitions, and
configurable formatting" with MiniMessage, documented placeholders and safe
escaping of user text. Through session 7 only Core met that contract
(`CoreMessages`); the feature modules shipped hard-coded feedback strings —
and RebellionItems even *bundled* a `messages.yml` that nothing read or
extracted. The session-7 boot smoke recorded this as an honest gap.

Wiring four-plus modules (items, teams, wards today; rpg, artifacts, dragons,
alchemy, prison, cosmetics still to come) needs one shared implementation of
the subtle parts: merging admin edits over bundled defaults, strict
MiniMessage well-formedness validation, the catch-all resolver that lets bare
runtime placeholders (`<detail>`, `<player>`) survive validation, prefix
handling, and atomic snapshot swapping on reload.

## Decision

1. **One `MessageCatalog` lives in `rebellion-api`
   (`net.zaminmc.rebellion.api.messages`).** Feature modules have a compile
   edge only on RebellionAPI — Core is a runtime plugin dependency — so the
   API is the only place all modules can share a single runtime class
   identity (same reasoning that placed `TeamAffiliationService` there in
   session 4). It is configuration infrastructure, not game logic, which
   keeps it inside the API's charter ("service interfaces and stable DTOs;
   no game database, command handlers, or feature logic").

2. **`CoreMessages` becomes a thin adapter** over `MessageCatalog.merge` /
   `parse` / render, keeping its public surface (`ValidationIssue` lists,
   `YamlFileIO` file errors, `load(Path, Logger)` signature) unchanged for
   Core's call sites. The strict-validation logic exists exactly once.

3. **Loading contract (boot and module reloads):** the bundled resource is
   copied into the module data folder on first boot and never written over
   admin edits afterwards. Admin values win; keys missing from the user file
   fall back to bundled defaults and are reported as warnings; a user
   template that fails strict validation falls back to the bundled default
   for that key and is reported — boot must come up. Unreadable file or
   missing bundled resource = enable failure (fail closed, like config.yml).
   User-only keys are kept unvalidated (admins may add entries this build
   does not know).

4. **`parse` (candidate validation) stays reject-whole-candidate** for Core's
   `/rebellion reload` contract: any invalid template rejects the entire
   reload so a half-edited file never half-applies. This preserves the
   existing, tested Core behavior; feature-module reloads use load
   semantics (per-key fallback + warning), which is strictly more available
   and still reports every problem precisely.

5. **Outcome keys are deterministic:** `team.<action>.<outcome>` /
   `ward.<action>.<outcome>` with the enum name lowercased and underscores
   as dashes (e.g. `team.kick.cant-target-leader`,
   `ward.create.decoys-disabled`). An admin who sees a message can find its
   key, and vice versa; per-module coverage tests enumerate every
   (action, outcome) pair against the shipped file. Texts identical across
   all actions share a top-level key (`not-in-team`, `name-invalid`);
   infrastructure failures (`DB_FAILURE`, `DB_ERROR`, `OVERLOADED`) share
   one fail-closed `common.unavailable` text.

6. **Dynamic values travel as placeholders, never as concatenated
   MiniMessage.** Player/ward/team names, distances and details are inserted
   with `Placeholder.unparsed` / `Placeholder.component`, so tag and
   click-event injection is *structurally impossible* — replacing the old
   angle-bracket-stripping `escape()` helpers. Chat channel formats
   (`chat.team-format`, `chat.proximity-format`) are templates over
   `<sender>` / `<message>` component placeholders, deserialized per
   message (chat is rate-gated and low volume).

7. **Literal angle brackets in templates escape only the opening bracket**
   (`/team invite \<player>`): MiniMessage has `\<` but no `\>` escape — a
   backslash before `>` stays literal text (verified against
   adventure-text-minimessage 5.2.0). Bare unknown tags would render
   leniently today but could change meaning if MiniMessage ever claims the
   name, so escapes are mandatory for literal brackets.

## Consequences

- Every module now ships a `messages.yml` extracted on first boot; all
  player feedback (commands, listings, alerts, chat formats, the
  anchor-break notice) renders through it. The console-refusal notices of
  dynamic menu commands and the menu console-action denial notice are now
  player-visible honest feedback instead of log lines.
- Two latent rendering defects surfaced and were fixed during the migration:
  the wards `list.empty` text shipped HTML entities (`&lt;name&gt;`) that
  rendered literally in game, and `admin.list-line` carried an unclosed
  `<gray>` that strict validation now rejects (lenient mode had masked it).
- MiniMessage escaping knowledge (`\<` yes, `\>` no) is recorded in the
  catalog javadoc and in every bundled `messages.yml` header.
- Session 7's roadmap note reserved "ADR-0012" for the dimension-artifact
  state machine; that work is **not** lost — it becomes ADR-0013 when
  Phase 6 starts.
- Rendered output changed visibly: command feedback now carries the
  configured `prefix` (`msg()`), listing lines do not (`raw()`) — the same
  convention Core's `/rebellion` surface established in session 2.

## Verification

- `MessageCatalogTest` (API, 8 tests) pins the shared contracts: strict
  parse, unclosed-tag rejection with placeholder exemption, load/extract,
  admin-edit precedence, per-key fallback for invalid edits, user-only keys,
  traversal-guarded resource names, merge≡load equivalence.
- Per-module coverage tests (`ItemsMessagesTest`, `TeamsMessagesTest`,
  `WardsMessagesTest`, +10 tests) strict-parse each shipped `messages.yml`
  and assert every rendered key and every (action, outcome) pair exists.
- Live boot smoke on Paper 26.3 build 226 (fresh server dir): first boot
  extracts all four `messages.yml` files; console feedback renders through
  the catalogs (`rebellionitems status`, `wardadmin list`, `team` console
  gate); an admin edit applies via `/rebellionitems reload` without restart;
  a deliberately unclosed template is reported with its key and the bundled
  default renders; restart keeps the admin file byte-identical with no
  re-extraction. Full suite: 307 tests green, stable across
  `--rerun-tasks`.
