# Security

## Threat model (current scope: foundation modules)

| Asset | Threat | Mitigation |
|---|---|---|
| Database credentials | leakage via files/logs/reports | env expansion `${REBELLION_DB_PASSWORD}`; diagnostics receive only backend names/booleans by construction; no credential keys are ever logged |
| Config files | malicious/hostile YAML (admins are trusted, files still validated) | schema-version + strict validation; path traversal in configured names rejected; unknown keys preserved but never executed |
| Player-facing messages | format-string abuse via templates | MiniMessage (not legacy `&` codes) with strict closed-tag validation; user text interpolated via `Placeholder.unparsed`, never re-parsed as markup |
| Persistence | overload / DoS | bounded queue with fail-closed rejection; no per-player threads; rejection precedes any SQL |
| Command surface | privilege escalation | `rebellion.admin*` nodes default op; per-subcommand re-check; no free-text SQL/paths/class names/`console` passthrough in any command |
| SQL injection | crafted input reaching SQL | prepared statements only (`Sql.bind` whitelists bindable types; unsupported types are rejected before execution — tested) |
| Unique assets (future) | duplication/minting | fail-closed rule: saturation or DB outage never mints/moves/consumes unique assets; enforced via `PERSISTENCE_OVERLOADED` contract |

## Item identity (design contract for Phase 2, enforcement pending)

Valuable items will be identified by versioned PersistentDataContainer keys
under the `rebellion:` namespace, never by display name or lore. Forged or
unknown IDs must fail closed with a sanitized diagnostic. This contract is
stated here because the rarity/identity module is the next vertical slice.

## Data privacy positions

- Public web-map markers are privacy-by-default (no ward locations, tracking
  data, or artifact origins exposed) — enforced when the map adapter exists.
- Placeholders never read the database and never expose staff-only state.
- Diagnostic reports never contain player data beyond what a caller
  explicitly passes (module states, config validation summaries).

## Dependency handling

- Compile-only deps (Paper API, PlaceholderAPI) are provided by the server;
  only HikariCP is bundled (shaded + relocated to avoid classpath clashes).
- No dependency downloads at runtime; no reflection-based plugin loading.
