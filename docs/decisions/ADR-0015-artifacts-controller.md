# ADR-0015 — Artifacts controller: protected-origin presentation, physical item flows, and the gated purge

Date: 2026-10-28 · Status: accepted (controller slice implemented, tested
(53 module tests) and boot-verified on Paper 26.3 — player flows are
interactive gates, per the ADR-0008/0014 precedent)

## Context

ADR-0013 delivered the artifacts persistence layer: dimension-keyed
registry, instance-stamped possession CAS, recoverable loss with the
return pipeline, and the once-per-occurrence Rebellion event — all
serverless, all tested. The controller phase (master prompt §5.5) must
now put the state machines in front of players: a **protected,
discoverable origin** presentation, **physical item flows** (capture,
steal, destruction → recoverable loss), the **catastrophe surface**
(global warnings, the explicitly configured world-wide purge power with
ritual/warning/cooldown/confirmation), and the commands/placeholders
contract. Nothing may weaken the ADR-0013 invariants: exactly three
rows, no duplicate valid copies, once-per-occurrence event, crash
recovery at every stage.

## Decision

### 1. The origin presentation is an ItemDisplay + Interaction pair, self-healing, never an inventory item

While a row is `ORIGIN`, the protected realm presents the artifact as a
tagged **ItemDisplay** (visual) + **Interaction** entity (click target)
at the config-owned anchor. Both carry the `artifact_key` in entity PDC.
The pair is **presentation, not state**: the row is the authority, so
the entities can vanish (chunk, restart, a stray `/kill` that slips a
cancel) without data loss — the **sweep reconciles** them (chunk loaded
⇒ exactly one presentation per ORIGIN row exists near the anchor;
duplicates removed; presentations for non-ORIGIN rows removed).

**Capture** is a right-click on the Interaction: read the row (must be
ORIGIN), build a fresh stamped item from the row's live instance, run
the `capture` CAS, remove the pair, deliver the item. A raced or cloned
click loses the CAS (`INSTANCE_STALE`/`INVALID_STATE`) — nothing is
delivered. **Inventory full refuses the capture** (the pair stays; no
mailbox hop for a player who is standing right there). Protection:
damage on the pair is cancelled (attacks, explosions and kills all
route through `EntityDamageEvent`). Paper 26.3's `EntityRemoveEvent`
is monitoring-only — it cannot be cancelled — so removal resilience is
the **self-healing reconcile** itself: any removal that slips through
some other path (another plugin's discard, a discard on kill) costs
nothing, because the row, not the entity, is the authority and the next
sweep restores the discoverable origin.

This deliberately ships **no decorative encounter structure** (the
build/POI around the anchor): the recovery logic — §5.5's actual hard
requirement — is the reconcile sweep plus the return pipeline. The
structure is recorded as remaining operator map work.

### 2. Item flows: possession changes ride the CAS; destruction means LOST, not protection

The physical artifact item is a configured material stamped in PDC with
the immutable `artifact_id` + live `instance` (the EggItems discipline).
Flows map onto ADR-0013 transitions, and **every one is idempotent by
the CAS**, so duplicate or racing Bukkit events are benign:

| Bukkit event | ADR-0013 transition |
|---|---|
| `EntityPickupItemEvent` (player, live stamp, picker ≠ holder) | `transfer(from=row holder, to=picker)` |
| `EntityPickupItemEvent` (picker = holder) | no-op (possession unchanged) |
| `EntityPickupItemEvent` (row is LOST, live stamp) | `found` (fresh stamp for the finder) |
| `InventoryClickEvent` (live-stamped stack lands with a new player — chest theft, trade UI) | `transfer` (same guard) |
| `EntityRemoveEvent` cause DESPAWN / DEATH / EXPLODE / OUT_OF_WORLD / DISCARD (item entity) | `markLost` (idempotent: `LOST_ALREADY`) — EntityRemoveEvent is monitoring-only on 26.3, so destruction is observed, not prevented |
| `InventoryPickupItemEvent` (hopper suction) | **cancelled** — a container-sucked item must not be misread as destruction |
| `CraftItemEvent` (live stamp as ingredient) | **cancelled** — crafting consumes silently; that hole stays closed |

Key readings of §5.5 encoded here: **chest storage is legal** (row stays
`HELD`; a thief taking it from the chest is a `transfer`), **dropped
items are stealable** (no pickup lock), and **destruction is valid
gameplay** — a burned/voided/despawned item becomes `LOST` (recoverable,
last holder kept), **not** cancelled. This is the deliberate opposite of
the dragons egg policy (despawn cancelled, nothing may destroy): dragon
lineages are finite-mint; artifacts are infinite-cycle assets with a
return pipeline. The despawn path is the natural reading of "dropped and
forgotten → lost".

### 3. The Rebellion observer is a dumb qualification probe after every possession change

After any transition that lands an artifact `HELD` (capture, transfer,
found), the observer reads the mirror: **all three HELD by the same
player** → `triggerRebellion`. The trigger's own transaction re-verifies
qualification and collapses duplicates through the guard CAS
(`EVENT_ALREADY_ACTIVE` carrying the same occurrence), so the observer
needs no dedup layer — master prompt's "do not accidentally trigger
three times" is structural. The catastrophe surface in this slice is
honest and bounded: a global broadcast on trigger, a join-time notice
while an occurrence is ACTIVE, a configurable periodic reminder, and a
configurable occurrence **duration** after which the sweep completes the
occurrence (admin can force-complete). Dimension-instability *effects*
(particles, ambient, empowered encounters) need live feel-tuning and are
recorded as remaining.

### 4. The purge: once-per-occurrence by CAS, telegraphed, confirmed, default-OFF

§5.5's world-wide kill power ships fully gated
(`artifacts.purge.enabled: false`):

1. **Config gate** — `enabled` (default false), `target-set: ONLINE`
   (`STORED` is a loud unimplemented error: there is no profiles module
   to enumerate stored records — the same loud-refusal discipline as the
   `TEAM` trigger scope), `spare-holder` (default true — §5.5: never
   accidentally kill the activating holder), `min-delay-minutes` (the
   telegraph between trigger and purge), `warning-seconds` (the final
   broadcast lead).
2. **State gate** — an ACTIVE occurrence must exist and the actor must
   be its trigger holder (the "valid ritual/state").
3. **Confirmation gate** — a literal `confirm` keyword; no free-text
   that could smuggle a command (§5.5: no command injection).
4. **Structural idempotency** — schema **v2** adds
   `purge_executed_utc` to `rb_artifacts_events`; the purge executes
   only through `purge_executed_utc IS NULL → now` (one CAS, audited).
   A retried command, a restart mid-warning or mid-execution resolves
   to the typed no-op `PURGE_ALREADY_EXECUTED` — the kill set runs
   exactly once per occurrence, forever.
5. **Outcome recording** — the executed purge broadcasts the counted
   outcome and appends the audit row in the same transaction as the
   stamp; disconnect/restart mid-event cannot double-kill (the stamp
   commits first; the kill loop is idempotent against a stamp that is
   already set).

`spendArtifacts` (the overpowering action's item half) rides the same
command surface: `/artifact spend` — ACTIVE occurrence, trigger holder,
literal confirm, all-or-none CAS; the three live stacks are removed from
the inventory only after the CAS commits. Maximum-health scars remain
Phase 9 work (they need the profiles module — §5.5 ties them to
persistent player records, and no such store exists yet).

## Consequences

- The controller adds **no new state** to ADR-0013 — every player-facing
  flow resolves to an existing CAS transition; the only schema change is
  the additive v2 column backing the purge's once-per-occurrence stamp.
- Presentation entities are self-healing cosmetics: losing them loses
  nothing; the sweep restores the discoverable origin.
- The artifacts' item-protection policy is intentionally weaker than the
  dragons' (destruction allowed → recoverable LOST) and that asymmetry
  is now a documented decision, not an oversight.
- Testing: config contract extensions (materials, purge bounds, STORED
  refusal), `ArtifactServiceIT` purge-CAS idempotency + append-only
  migration-set pin, a pure purge-selection test, and the full
  messages-catalog pin; boot smoke on Paper 26.3 covers
  enable/migrate/seed/restart plus a live forceload-driven proof of the
  reconcile (exactly one pair; kill one half or both halves converges
  back to 1+1 within one sweep — a partial-pair orphan bug was caught
  and fixed during that proof).
- Measured 26.3 constraints recorded: `Material.isAir()/isItem()` need
  RegistryAccess (unavailable serverless — validated by name + air
  constants instead); `EntityRemoveEvent` is monitoring-only; `/kill`
  on a non-living display bypasses damage events, so presentation
  protection is damage-cancel plus the self-healing reconcile; on an
  empty server anchor chunks are not loaded, so presentations
  (deliberately) wait for chunk load — players bring the chunks.
- Remaining interactive gates: a player capturing from the presentation,
  the steal/destruction flows, the trigger broadcast, and the purge kill
  loop are server-side implemented and boot-verified only — no player
  joined the smoke server.
