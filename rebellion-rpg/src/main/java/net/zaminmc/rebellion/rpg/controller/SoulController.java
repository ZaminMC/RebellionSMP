package net.zaminmc.rebellion.rpg.controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.rpg.Encounter;
import net.zaminmc.rebellion.rpg.EncounterDefinition;
import net.zaminmc.rebellion.rpg.EncounterState;
import net.zaminmc.rebellion.rpg.RpgConfig;
import net.zaminmc.rebellion.rpg.RpgOutcome;
import net.zaminmc.rebellion.rpg.RpgResult;
import net.zaminmc.rebellion.rpg.RpgService;
import net.zaminmc.rebellion.rpg.TrackingSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Soul-encounter controller (ADR-0017): the entity half of the
 * encounter state machine riding the ADR-0016 ledger rows. All entity
 * and world operations run on the main thread; every rule that is state
 * (concurrency caps, cooldowns, TTL, one-winner rewards) is answered by
 * the service's ledger CAS, never by this class. The controller only
 * decides <em>where and whether to try</em> using the pure
 * {@link SpawnRules} verdicts, so the spawner stays deterministic and
 * debuggable (master prompt §5.3).
 *
 * <ul>
 *   <li><strong>Spawner:</strong> one cycle per {@code spawn-interval};
 *       at most one begin attempt per enabled encounter key. Sites are
 *       drawn around a random eligible anchor player (allowed world +
 *       time window + biome + player-count rules), within the configured
 *       radius and at the anchor's ground level so no chunk is
 *       force-loaded for a spawn.</li>
 *   <li><strong>Souls are protected quest givers:</strong> the spawned
 *       entity is made persistent, never despawns by distance, keeps
 *       aware-off for a stable deterministic presence, and the listener
 *       cancels all damage against it. The physical entity is
 *       replaceable - the ledger row is the authority.</li>
 *   <li><strong>Completion:</strong> the first helper who interacts wins
 *       the one-winner CAS; everyone else resolves to the typed no-op.
 *       The entity is removed only after the CAS commits.</li>
 *   <li><strong>Reconciliation:</strong> the sweep releases stale claim
 *       reservations, expires TTL-elapsed encounter rows and removes
 *       entities still standing for expired rows. A soul entity removed
 *       for any cause except chunk-unload despawns its row (the E7
 *       lesson: UNLOAD means the entity persists on disk).</li>
 * </ul>
 */
public final class SoulController {

    private final Plugin plugin;
    private final RpgService service;
    private final RpgConfig config;
    private final RpgControllerConfig controller;
    private final MessageCatalog messages;
    /** Live soul entities: entity uuid -> encounter id (protection map). */
    private final Map<UUID, UUID> souls = new ConcurrentHashMap<>();

    public SoulController(@NotNull Plugin plugin, @NotNull RpgService service,
            @NotNull RpgConfig config, @NotNull RpgControllerConfig controller,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.controller = controller;
        this.messages = messages;
    }

    // ------------------------------------------------------------------
    // Spawner
    // ------------------------------------------------------------------

    /** One spawn cycle (main thread, task-owned): at most one begin per key. */
    public void spawnTick() {
        for (EncounterDefinition definition : config.encounters().values()) {
            if (definition.enabled()) {
                trySpawn(definition);
            }
        }
    }

    private void trySpawn(@NotNull EncounterDefinition definition) {
        int online = Bukkit.getOnlinePlayers().size();
        if (!SpawnRules.playerCountMatches(online, definition.minPlayers(),
                definition.maxPlayers())) {
            return;
        }
        Player anchor = pickAnchor(definition);
        if (anchor == null) {
            return;
        }
        Location site = siteAround(anchor, definition);
        if (site == null) {
            return;
        }
        // The ledger is the authority: entities spawn only against a begun
        // row, and a refused begin (cap/cooldown) spawns nothing.
        service.beginEncounter(definition.key(), site.getWorld().getName(),
                site.getBlockX(), site.getBlockY(), site.getBlockZ())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin,
                        () -> spawnEntity(result, site)));
    }

    private Player pickAnchor(@NotNull EncounterDefinition definition) {
        Player[] eligible = Bukkit.getOnlinePlayers().stream()
                .filter(p -> definition.allowsWorld(p.getWorld().getName()))
                .filter(p -> SpawnRules.timeWindowMatches(definition.timeWindow(),
                        p.getWorld().getTime()))
                .toArray(Player[]::new);
        return eligible.length == 0 ? null
                : eligible[ThreadLocalRandom.current().nextInt(eligible.length)];
    }

    /**
     * Spawn site: a random point in the configured radius around the
     * anchor, at the anchor's ground Y. Stays inside the anchor's loaded
     * surroundings, and the biome rule is evaluated at the drawn site -
     * a biome-restricted soul only spawns where its biome actually is.
     */
    private Location siteAround(@NotNull Player anchor,
            @NotNull EncounterDefinition definition) {
        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        double distance = ThreadLocalRandom.current().nextDouble()
                * controller.spawnRadiusBlocks();
        int x = anchor.getLocation().getBlockX() + (int) Math.round(
                Math.cos(angle) * distance);
        int z = anchor.getLocation().getBlockZ() + (int) Math.round(
                Math.sin(angle) * distance);
        String biome = biomeKeyAt(anchor.getWorld(), x, z);
        if (biome == null || !SpawnRules.biomeAllowed(definition.biomes(), biome)) {
            return null;
        }
        return new Location(anchor.getWorld(), x + 0.5, anchor.getLocation().getY(),
                z + 0.5);
    }

    /** Biome key at a column, or null when it cannot be read without
     *  loading terrain (an unloaded column never spawns a soul). */
    private String biomeKeyAt(@NotNull org.bukkit.World world, int x, int z) {
        try {
            var key = world.getBiome(x, z).getKey();
            return key == null ? null : key.getKey();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void spawnEntity(@NotNull RpgResult<Encounter> result,
            @NotNull Location site) {
        if (result.outcome() != RpgOutcome.ENCOUNTER_BEGUN
                || result.payload() == null) {
            return; // typed refusal: nothing spawned
        }
        Encounter encounter = result.payload();
        EncounterDefinition definition = config.encounters().get(encounter.key());
        if (definition == null) {
            service.despawnEncounter(encounter.encounterId(), "definition vanished");
            return;
        }
        org.bukkit.entity.Entity spawned = site.getWorld().spawn(site,
                definition.soulEntity().getEntityClass(), entity -> {
                    entity.setPersistent(true);
                    if (entity instanceof LivingEntity living) {
                        living.setRemoveWhenFarAway(false);
                    }
                    if (entity instanceof org.bukkit.entity.Mob mob) {
                        mob.setAware(false); // stable, deterministic presence
                    }
                });
        if (!(spawned instanceof LivingEntity soul)) {
            spawned.remove();
            service.despawnEncounter(encounter.encounterId(),
                    "soul entity type is not living");
            return;
        }
        service.attachSoul(encounter.encounterId(), soul.getUniqueId())
                .thenAccept(attach -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (attach.outcome() != RpgOutcome.SOUL_ATTACHED
                            || attach.payload() == null) {
                        // Row resolved between begin and attach (TTL raced
                        // in): the entity never becomes a live soul.
                        soul.remove();
                        return;
                    }
                    souls.put(soul.getUniqueId(), encounter.encounterId());
                }));
    }

    // ------------------------------------------------------------------
    // Completion + protection map
    // ------------------------------------------------------------------

    /** @return whether the entity is a live soul (damage-protection map). */
    public boolean isLiveSoul(@NotNull UUID entity) {
        return souls.containsKey(entity);
    }

    /**
     * First-helper completion: the one-winner CAS grants the rewards;
     * losers resolve to the typed no-op. The entity leaves only after
     * the CAS answer, whatever it is.
     */
    public void interactComplete(@NotNull Player helper, @NotNull UUID entity) {
        UUID encounterId = souls.get(entity);
        if (encounterId == null) {
            return;
        }
        service.completeEncounter(encounterId, helper.getUniqueId())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    removeSoul(entity);
                    if (result.outcome() == RpgOutcome.ENCOUNTER_COMPLETED) {
                        EncounterDefinition definition =
                                config.encounters().get(
                                        result.payload() == null ? ""
                                                : result.payload().key());
                        String reward = definition == null || definition.rewards().isEmpty()
                                ? "unknown" : definition.rewards().get(0);
                        helper.sendMessage(messages.msg("soul.interact.ok",
                                Placeholder.unparsed("key", reward)));
                    } else if (result.outcome()
                            == RpgOutcome.ENCOUNTER_ALREADY_RESOLVED) {
                        helper.sendMessage(messages.msg("soul.interact.already"));
                    } else {
                        helper.sendMessage(messages.msg("soul.interact.not-active"));
                    }
                }));
    }

    /**
     * Entity vanished for a non-unload cause: despawn the row (unwedge;
     * counts as resolved so the cooldown can run again). UNLOAD is
     * skipped - the entity persists with its chunk (the E7 lesson).
     */
    public void soulRemoved(@NotNull UUID entity,
            org.bukkit.event.entity.EntityRemoveEvent.Cause cause) {
        UUID encounterId = souls.remove(entity);
        if (encounterId == null || cause == org.bukkit.event.entity.EntityRemoveEvent.Cause.UNLOAD) {
            return;
        }
        service.despawnEncounter(encounterId, "soul entity removed (" + cause + ")");
    }

    /** Removes the protection-map entry (entity leaving cleanly). */
    public void removeSoul(@NotNull UUID entity) {
        souls.remove(entity);
    }

    // ------------------------------------------------------------------
    // Reconciliation sweep
    // ------------------------------------------------------------------

    /** One reconciliation cycle (main thread, task-owned). */
    public void sweep() {
        service.releaseStaleReservations(
                        controller.reservationStaleSeconds() * 1000L)
                .thenAccept(released -> {
                    if (released > 0) {
                        plugin.getLogger().info("sweep released " + released
                                + " stale ritual reservation(s)");
                    }
                });
        service.expireEncounters().thenAccept(expired -> {
            if (expired > 0) {
                plugin.getLogger().info("sweep expired " + expired
                        + " soul encounter(s) past TTL");
            }
        });
        // Entities still standing for non-ACTIVE rows are cleaned up; the
        // map entry goes first so the protection listener never shields a
        // corpse. Rows whose entity is merely UNLOADED are untouched.
        service.encounters(EncounterState.EXPIRED).thenAccept(rows ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Encounter encounter : rows) {
                        if (encounter.soulEntity() == null) {
                            continue;
                        }
                        org.bukkit.entity.Entity entity =
                                Bukkit.getEntity(encounter.soulEntity());
                        if (entity != null && entity.isValid()) {
                            souls.remove(entity.getUniqueId());
                            entity.remove();
                        }
                    }
                }));
    }

    // ------------------------------------------------------------------
    // Tracking-signal rendering (pulse + on-demand)
    // ------------------------------------------------------------------

    /** Renders one caster's active sessions as compass/range signals. */
    public void renderSignals(@NotNull Player caster) {
        service.resolveTracking(caster.getUniqueId()).thenAccept(views ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (views.isEmpty()) {
                        caster.sendMessage(messages.msg("ritual.signal.none"));
                        return;
                    }
                    caster.sendMessage(messages.msg("ritual.signal.header"));
                    for (var view : views) {
                        caster.sendMessage(signalLine(caster, view));
                    }
                }));
    }

    /** Pulses every online player's sessions (bounded by online count). */
    public void signalPulse() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderSignals(player);
        }
    }

    private @NotNull net.kyori.adventure.text.Component signalLine(
            @NotNull Player caster,
            @NotNull RpgService.TrackingView view) {
        TrackingSession session = view.session();
        TagResolver target = Placeholder.unparsed("target",
                String.valueOf(session.targetUuid()));
        if (view.fullyMasked()) {
            return messages.msg("ritual.signal.masked", target);
        }
        Player online = Bukkit.getPlayer(session.targetUuid());
        if (online == null || !online.isOnline()) {
            return messages.msg("ritual.signal.offline", target);
        }
        // The effective tier after mask degradation bounds what the line
        // may reveal; REGION wording is compass + bucket only, never a
        // coordinate (SignalMath is the whole REGION vocabulary).
        int dx = online.getLocation().getBlockX() - caster.getLocation().getBlockX();
        int dz = online.getLocation().getBlockZ() - caster.getLocation().getBlockZ();
        TagResolver direction = Placeholder.unparsed("direction",
                SignalMath.compass8(dx, dz));
        TagResolver range = Placeholder.unparsed("range",
                rangeLabel(SignalMath.bucketIndex(
                        SignalMath.horizontalDistance(0, 0, dx, dz),
                        controller.distanceBuckets()),
                        controller.distanceBuckets().size()));
        return messages.msg("ritual.signal.line", target, direction, range);
    }

    /** Fixed signal vocabulary for any bucket count (config carries numbers). */
    private @NotNull String rangeLabel(int bucket, int buckets) {
        if (bucket >= buckets) {
            return plainOf("ritual.signal.range.beyond");
        }
        return switch (bucket) {
            case 0 -> plainOf("ritual.signal.range.close");
            case 1 -> plainOf("ritual.signal.range.mid");
            default -> plainOf("ritual.signal.range.far");
        };
    }

    private String plainOf(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(messages.raw(key));
    }

    /** Test/diagnostic view of the live-soul map size (bounded by caps). */
    public int liveSouls() {
        return souls.size();
    }
}
