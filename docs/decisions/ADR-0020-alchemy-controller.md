# ADR-0020 — Alchemy controller slice: brewing XP wiring, POWER boosts, potion bags, auto-consumption execution, commands and placeholders

Date: 2026-10-28 · Status: accepted (RebellionAlchemy becomes the ninth
installable plugin; the persistence contract it rides is ADR-0019's)

## Context

ADR-0019 pinned every §5.7 invariant that is *state*: derived brewing
levels over an idempotent atomic XP accumulate, the two-path unlock ledger
bought through the claim protocol, player-defined auto-consumption rules
under a strict operator policy, and execution claims that make potion
consumption and the cooldown stamp one logical operation. What it
deliberately left to this slice is everything that needs a live inventory,
a live player, or the Bukkit registry: where XP comes from, what a POWER
unlock actually does to a drunk potion, what a potion bag *is*, how a rule
*fires*, and the `/brewing` surface. Master prompt §19's bar for this
slice: no placeholder command is presented as implemented functionality,
the boot smoke gates the wiring on real Paper 26.3, and every state change
still rides the tested service.

Live-API facts proven before implementation (javap against the pinned
paper-api 26.3 build 226 jar): `BrewEvent` exposes `getContents()` +
`getResults()` and is cancellable; `PotionMeta` has
`getBasePotionType()`/`getAllEffects()` (the old `org.bukkit.potion.Potion`
class no longer exists); `PotionType.getEffectType()` is the sanctioned
live effect mapping (registry-bound — the exact boundary ADR-0019 drew:
config/rules speak `PotionType`, only live code touches the effect type);
`Inventory.removeItem`/`addItem` return slot→leftover maps;
`Bukkit.createInventory(holder, size, Component)` supports custom holders;
block `PersistentDataContainer`s exist for the brewing-stand ownership
stamp; `InventoryMoveItemEvent` is cancellable (the hopper guard);
`Damageable.getHealth/getMaxHealth`, `Entity.getFireTicks/getFallDistance/
isOnGround` and `WorldInfo.getEnvironment()` are the trigger inputs.

## Decision

### 1. Brewing XP: stands are claimed by their ingredient loaders

Brewing stands are anonymous — no Bukkit event says *who* brewed. The
honest options were crediting the block's placer or the last player who
loaded its ingredient slot; this slice ships the **loader stamp**: the
stand block's PDC carries the UUID of the last player who placed an item
into its ingredient slot (stamped on place by `BlockPlaceEvent`, refreshed
whenever a player clicks an item into the ingredient slot of a live
`BrewerInventory`). A brew completing on an unstamped stand earns nothing
(logged at fine — pre-existing stands from before the plugin go quiet
until someone touches them, which is both safe and self-healing).

`BrewEvent` (MONITOR, ignoreCancelled) awards the stamped loader
`xp-per-brew × completed result slots`. The deterministic source key —
the ADR-0019 idempotency contract — is
`brew:<world>:<x>:<y>:<z>:<second-bucket>`: brewing cycles take 400 ticks,
so two completions on one stand can never share a one-second bucket, while
a re-delivered event inside the same second resolves to the typed no-op
the persistence layer already pins. The award is a fire-and-forget service
call; there is no player message (the brewer may be offline) — progress
surfaces through `/brewing status` and the placeholders, and every award
is in the audit trail.

### 2. POWER unlocks apply at drink time, not at brew time

Brewed items stay vanilla. When the holder of a POWER unlock drinks a
potion of the matching `PotionType`, the unlock rewrites the applied
effect: `amplifier + amplifier-bonus` and `duration × duration-multiplier`
over the potion's *own* effect values (`PotionMeta.getAllEffects()`), so a
stronger brewed/tiered potion stays stronger — the unlock scales what was
actually in the bottle, never mints a flat template. Applied in
`PlayerItemConsumeEvent` at MONITOR after vanilla's application, via
`addPotionEffect` force — an override, not a stack. The pure math lives in
`PotionBoost` and is serverless-tested; the unlock lookup goes through the
join-loaded player cache (a cold cache means an honestly missed boost,
never a blocking read in the event handler). The auto-consume path applies
the same boost — one function, two callers.

EFFICIENCY grants: `cost-reduction` is applied by `/brewing unlock` when
composing the purchase's cost list (each item amount reduced, floored at
1 — a reduction can never mint a free component); `bag-capacity-bonus`
widens the opener's bag capacity at open time (bounded by the global 54
slot ceiling); `auto-cooldown-reduction` shortens a rule's effective
cooldown at evaluation, floored at the policy minimum. All three are pure
math over config + row values, pinned serverless.

### 3. Potion bags: contents ride the item's PDC, persistence is per-click

A bag item is the definition's material stamped with a unique `bag_id`,
the definition key and a contents payload (Base64 `BukkitObjectOutputStream`
over the item list) in its PDC. `/alchemyadmin bag` mints (the only mint
path — no free/player minting in this slice, mirroring the items module's
restricted-mint precedent); unique `bag_id`s prevent stacking. Opening
(right-click, after the definition's `required-unlock` gate is checked
against the opener's cache) renders a `CHEST`-shaped inventory of
`capacity + opener's bonus` slots backed by a `BagHolder` that captures the
live item instance.

**Contents-in-PDC is the whole safety design.** The open inventory is a
transient mirror; the PDC is the authority, persisted after *every* click
and drag that touches the bag inventory — not on close. That kills the
crash-duplication window (crash mid-open loses at most the in-flight
event, exactly like every other inventory interaction, and never leaves
the item countable in two places). The §5.7 inventory-safety rules:

- **No nesting**: any click/drag that would move a bag item into a bag
  inventory is cancelled (bags never contain bags — recursion and PDC
  size explosion are structurally impossible).
- **No shift-click/drag/cursor dupes**: all movement goes through
  Bukkit's own click/drag handling against the mirror; nothing clones
  stacks, and the per-click persist writes the final state back.
- **Death/drop**: the bag item carries its PDC wherever vanilla takes it
  (drops, chests, keepInventory) — no listener needed, nothing to lose.
- **Crafting/smelting**: a bag as a crafting ingredient blocks the
  result; hoppers never move bag items (`InventoryMoveItemEvent`
  cancelled for bags — documented as "bags don't ride hoppers"); clicks
  that would insert a bag into a furnace inventory are cancelled.
- **Close**: one final persist; the cursor stack returns to the player
  by vanilla's own close handling.

Honest edge, documented: a bag item destroyed mid-open (void, cactus —
the guards above only stop *container* routing) loses its contents with
it — the item was the storage. Not a duplication vector.

### 4. Auto-consumption: one periodic evaluator, the claim protocol is the discipline

A single repeating task (default every 2 s) evaluates every online
player's live rules (join-loaded cache) in the deterministic conflict
order. Per trigger, at most **one** rule per cycle: the first enabled rule
whose condition matches — `AutoTriggers` pure predicates over
health-ratio / fire-ticks / environment / fall-state (serverless-tested;
the FALLING threshold ships in the controller config since the trigger
itself is policy-disabled by default). Evaluation → execution follows
ADR-0019's protocol exactly:

1. `reserveAutoConsume` (row-authoritative: ownership, liveness, enabled,
   cooldown-after-reduction, one reservation per rule);
2. on the main thread, locate the potion in the rule's storage scope
   (BAG scopes search bag PDC payloads, INVENTORY scopes search storage +
   off-hand, ANY searches bags then inventory), remove it *by slot/PDC
   surgery* (exact, no fuzzy matching), apply its own effects through the
   boost function — any failure → `releaseReservation` (the safe
   fallback, no cooldown bought);
3. `commitAutoConsume` stamps the cooldown authority, all-or-none.

The digest is the located potion's description (`potion:<type>:1:scope`),
the audit row is the "debug reason", and the optional player notify
message (controller config, default on) renders the trigger, potion and
scope — the §5.7 audit/debug-reason surface, operator-tunable.

### 5. Commands, placeholders, and the sweep

- `/brewing status|unlocks|unlock <key>|rules|rules add|rules toggle|rules
  remove` — the purchase driver is the same closed sequence as
  `/ritual`'s: verify cost presence (pure `Costs` math, efficiency
  reduction applied) → `reserveUnlock` → `removeItem` with leftover
  detection and exact refund → `commitUnlock`; every typed outcome maps
  to a message key. Rule management drives the service directly (all its
  gates are row/config-side) and refreshes the cache.
- `/alchemyadmin events <player>|grant <player> <key>|bag <player> <key>
  [amount]` — audit, the ADR-0019 admin-grant path, and the restricted
  bag mint. Console-legal; targets resolve to known UUIDs only.
- Placeholders (the shared `rebellion` expansion, viewer-scoped through
  the ADR-0018 contributor contract): `%rebellion_brewing_level%` (the
  master prompt §10 minimum), `%rebellion_brewing_total_xp%`,
  `%rebellion_brewing_next_level_xp%`, and
  `%rebellion_brewing_unlock_<key>%` rendered with operator-localized
  owned/unowned wording (the session-15 lesson: menus and PAPI share one
  vocabulary, MiniMessage-colored, not bare booleans).
- The periodic sweep releases stale claims (ADR-0019's crash-recovery
  contract) and runs once more at disable — reverse-order shutdown keeps
  Core's database alive underneath it.

### 6. What stays open (honest gates)

Inventory-safety edge cases, drink-boost feel, bag UX and auto-consume
timing are **interactive gates**: the boot smoke proves the nine jars
load, the descriptors, migrations, commands, placeholders and listeners
register, and the console surface answers — player-level flows stay
unclaimed until exercised by a player. Custom brewing *recipes* (stand
recipes beyond vanilla) are still future scope; §5.7's "advanced recipes"
read as the POWER unlock grants shipped here (§2), recorded so the ledger
stays truthful.

## Consequences

- Every state change still goes through the tested ADR-0019 service; the
  controller adds zero new persistence paths and zero in-memory authority
  — caches are read-only mirrors with join/quit lifecycles.
- XP can never double-award (per-second source buckets + the event-PK
  gate); purchases can never eat items without a committed unlock
  (claim protocol); executions can never buy cooldown without consuming
  (claim protocol); bags can never dupe (PDC authority, per-click
  persist, nesting ban, container guards).
- Serverless test surface: `Costs` (reduction math, presence checks),
  `AutoTriggers` (predicate bounds), `BrewSourceKeys` (composition +
  grammar), `PotionBoost` (amplifier/duration math), controller-config
  bounds, the message catalog pin and the placeholder shape pins. Live
  surfaces (listeners, codec, inventory interactions) are boot-smoke
  gated, mirroring the ADR-0015/0017 evidence split.
- Boot smoke now installs NINE jars — RebellionAlchemy last, after
  RebellionRPG.
