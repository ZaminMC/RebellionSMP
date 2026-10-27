# Configuration

All files live in the owning plugin's data folder (`plugins/<PluginName>/`)
and are created from bundled resources on first boot. Local edits are never
overwritten. Every file carries `schema-version`.

## RebellionCore

### config.yml
| Key | Type | Default | Notes |
|---|---|---|---|
| `schema-version` | int | 1 | future migrations keyed off this |
| `debug` | boolean | false | verbose logging; not for permanent production use |
| `startup-timeout-seconds` | int ≥ 1 | 30 | how long feature modules wait for the DB bootstrap |
| `diagnostics.directory` | string | `diagnostics` | subfolder for reports; path-traversal rejected by loader |
| `diagnostics.max-report-files` | int ≥ 1 | 10 | rotation bound |
| `diagnostics.recent-incident-capacity` | int ≥ 1 | 100 | in-memory incident ring size |

### database.yml
| Key | Type | Default | Notes |
|---|---|---|---|
| `backend` | `sqlite` \| `mysql` | sqlite | unknown values fail startup |
| `sqlite.file` | string | `rebellion.sqlite` | resolved inside the data folder |
| `mysql.host/port/database/username/password` | strings | localhost:3306 | password supports `${ENV}` / `${ENV:fallback}` |
| `mysql.properties` | map | `{}` | verbatim JDBC URL properties |
| `pool.max-pool-size` | int ≥ 1 | 10 | **ignored for SQLite: forced to 1** |
| `pool.min-idle` | int ≥ 1 | 2 | applies to MySQL |
| `pool.connection-timeout-ms` | int ≥ 100 | 5000 | Hikari connection timeout |
| `pool.max-lifetime-ms` | int ≥ 1 | 1800000 | Hikari max lifetime |
| `executor.pool-size` | int ≥ 1 | 4 | bounded persistence workers |
| `executor.queue-capacity` | int ≥ 1 | 2000 | hard backpressure bound; saturation rejects with incident |
| `migrations.strict-checksums` | boolean | true | refuse to boot on edited applied migrations |

### messages.yml
MiniMessage templates. Contract: **every tag explicitly closed**; unclosed
tags are rejected at load with the offending key. Runtime placeholders
(`<version>`, `<incident>`, …) are single identifiers and are exempt from
validation. Missing keys fall back to bundled defaults (reported, non-fatal).
Prefix is the `prefix` key and is prepended by `msg()`.

## Validation behavior (all loaders)

- YAML syntax error → module fails to enable (fail closed, actionable message).
- Aggregated issues: every invalid entry is reported at once with
  `path: explanation`.
- Reload (`/rebellion reload`): candidate parsed and validated fully before
  the active snapshot is swapped; on failure the previous snapshot stays and
  the issues are printed. `database.yml` is validated only — restart required
  to apply (stated in-game).
- Secrets: never logged, never copied into diagnostic reports; env expansion
  keeps credentials out of the file.
- Path traversal: file/directory names containing `..` or absolute escapes are
  rejected.

## Per-module configuration rule (binding for feature modules)

Each feature module ships its own `config.yml` + `messages.yml` (and focused
files like `items.yml`, `rarities.yml`, `menus/*.yml` where that improves
editability). No shared mega-config. An API-only module may omit config if it
genuinely has no settings — RebellionAPI is that exception today.
