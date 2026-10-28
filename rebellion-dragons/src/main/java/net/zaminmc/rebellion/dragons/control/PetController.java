package net.zaminmc.rebellion.dragons.control;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.api.teams.TeamAffiliationService;
import net.zaminmc.rebellion.dragons.DragonsConfig;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageOutcome;
import net.zaminmc.rebellion.dragons.LineageResult;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.egg.EggItems;
import net.zaminmc.rebellion.dragons.policy.FireballBudget;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pet-dragon entity operations on the ADR-0008-proven surface. Every recipe
 * here was measured live on Paper 26.3 build 226; nothing is guessed:
 *
 * <ul>
 *   <li><strong>Platform:</strong> {@code setAware(false)} + {@code setPhase(HOVER)}
 *       held position with 0.00 blocks drift over 5 s (E1) — used for
 *       hatching, calls, dismiss and the mounted base state.</li>
 *   <li><strong>Steering:</strong> closed-loop podium re-targeting
 *       ({@code setPodium} + {@code FLY_TO_PORTAL}), measured ~16 blocks/s
 *       with overshoot-then-park (E2c); the phase guard lives in the change-
 *       phase listener. Velocity writes and the ground pathfinder are dead
 *       ends on flying bosses (E2a/E2b) and are never used.</li>
 *   <li><strong>Fireballs:</strong> plugin-launched {@link DragonFireball}
 *       with direction + acceleration + power + velocity (E6 — direction
 *       alone leaves it motionless); budgeted per dragon by
 *       {@link FireballBudget} before anything spawns.</li>
 *   <li><strong>Health:</strong> {@code heal()} clamps at max with no phase
 *       interference (E5); bare sourceless {@code damage()} is filtered by
 *       vanilla (E3) and is never used.</li>
 * </ul>
 *
 * <p>All entity/world operations run on the main server thread (command
 * executors, interact listeners and the registered tasks); database work is
 * delegated to {@link LineageService}'s async transactions, and entity
 * side effects happen only after the relevant CAS commits. The controller
 * holds no {@code Player}/{@code World} references in long-lived maps —
 * UUIDs only, resolved fresh per use.</p>
 */
public final class PetController {

    private final Plugin plugin;
    private final LineageService lineages;
    private final DragonsConfig config;
    private final FireballBudget budget;
    private final EggItems eggs;
    private final MessageCatalog messages;
    private final TeamAffiliationService teams;

    /** Mounted pets: pet entity uuid -> riding owner uuid (steer loop input). */
    private final Map<UUID, UUID> mounted = new ConcurrentHashMap<>();
    /** Per-owner call cooldown stamps (in-memory; calls are not rare assets). */
    private final Map<UUID, Long> lastCallUtc = new ConcurrentHashMap<>();
    /** Live plugin-launched fireballs: fireball uuid -> launch record.
     *  Needed because world.spawn leaves the projectile shooter-less, so the
     *  hit-event listener identifies our launches by UUID. Bounded by the
     *  budget (<= maxPerWindow per dragon) and pruned by age on every hit. */
    private final Map<UUID, Launch> fireballs = new ConcurrentHashMap<>();

    /** Impact breath-clouds left by plugin-launched fireballs: cloud uuid ->
     *  cloud record. A friendly direct hit is cancelled wholesale, but a
     *  hostile/neutral impact near the owner still leaves a lingering
     *  vanilla cloud (E6); the damage shield resolves those by UUID. Bounded
     *  by the fireball budget and pruned by age like the fireball set. */
    private final Map<UUID, Cloud> breathClouds = new ConcurrentHashMap<>();

    /** One plugin fireball launch: the owning pet + the spawn stamp. */
    private record Launch(@NotNull UUID pet, long atUtc) {
    }

    /** One impact breath-cloud: the owning pet's owner + the spawn stamp. */
    private record Cloud(@NotNull UUID owner, long atUtc) {
    }

    public PetController(@NotNull Plugin plugin, @NotNull LineageService lineages,
            @NotNull DragonsConfig config, @NotNull EggItems eggs,
            @NotNull MessageCatalog messages, @Nullable TeamAffiliationService teams) {
        this.plugin = plugin;
        this.lineages = lineages;
        this.config = config;
        this.eggs = eggs;
        this.messages = messages;
        this.teams = teams;
        this.budget = new FireballBudget(config.fireballMaxPerWindow(),
                config.fireballWindowMs(), config.fireballMinIntervalMs());
    }

    /** Called by the dismount/mount listeners to keep the steer set honest. */
    public void riderMounted(@NotNull UUID petEntity, @NotNull UUID rider) {
        mounted.put(petEntity, rider);
    }

    public void riderDismounted(@NotNull UUID petEntity) {
        mounted.remove(petEntity);
    }

    public boolean isMounted(@NotNull UUID petEntity) {
        return mounted.containsKey(petEntity);
    }

    /** @return the owner's HATCHED lineage, when they own one. */
    public Optional<Lineage> hatchedOf(@NotNull UUID owner) {
        return lineages.all().stream()
                .filter(l -> l.state() == net.zaminmc.rebellion.dragons.LineageState.HATCHED)
                .filter(l -> owner.equals(l.owner()))
                .findFirst();
    }

    /** @return the living pet entity handle, when currently loaded. */
    public Optional<EnderDragon> livingPet(@NotNull Lineage lineage) {
        return lineage.entityOpt().flatMap(this::dragon);
    }

    private Optional<EnderDragon> dragon(@NotNull UUID entityUuid) {
        Entity e = Bukkit.getEntity(entityUuid);
        return e instanceof EnderDragon dragon && e.isValid() ? Optional.of(dragon)
                : Optional.empty();
    }

    /**
     * Puts a pet onto the measured stable platform: aware off, HOVER. Used
     * at hatch, after calls, on dismount and by dismiss. The inverse of the
     * steer loop's control state.
     */
    public void settle(@NotNull EnderDragon pet) {
        pet.setAware(false);
        pet.setPhase(EnderDragon.Phase.HOVER);
    }

    // ------------------------------------------------------------------
    // Hatch
    // ------------------------------------------------------------------

    /**
     * Hatches the egg stack in the player's main hand: validates the PDC
     * identity against the live registry, spawns the pet in front of and
     * above the holder, and runs the hatch CAS. Entity spawn happens
     * <em>before</em> the CAS (the entity UUID must exist to be bound); a
     * refused CAS (clone, stale stamp, concurrent click) removes the spawn
     * again so no orphan dragon and no consumed egg can result. The egg
     * item is consumed only after the CAS commits (ADR-0009 §4).
     */
    public void hatch(@NotNull Player holder, @NotNull ItemStack eggStack) {
        EggItems.StampedIdentity stamped = eggs.read(eggStack);
        if (stamped == null) {
            holder.sendMessage(messages.msg("dragon.hatch.no-egg"));
            return;
        }
        Optional<Lineage> found = lineages.byEggInstance(stamped.eggInstance());
        if (found.isEmpty() || !found.get().lineageId().equals(stamped.lineageId())) {
            holder.sendMessage(messages.msg("dragon.hatch.conflict"));
            return;
        }
        Lineage lineage = found.get();
        if (!holder.getUniqueId().equals(lineage.owner())) {
            holder.sendMessage(messages.msg("dragon.hatch.not-your-egg"));
            return;
        }
        Location at = spawnLocationFor(holder);
        EnderDragon pet = at.getWorld().spawn(at, EnderDragon.class);
        settle(pet);
        UUID entityUuid = pet.getUniqueId();
        lineages.hatch(lineage.lineageId(), stamped.eggInstance(), holder.getUniqueId(),
                entityUuid, config.hatchHealth())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == LineageOutcome.HATCHED) {
                        eggStack.setAmount(eggStack.getAmount() - 1);
                        persistNow(pet, lineages.byId(lineage.lineageId()).orElse(lineage));
                        holder.sendMessage(messages.msg("dragon.hatch.ok",
                                Placeholder.unparsed("slot", String.valueOf(lineage.slot())),
                                Placeholder.unparsed("x", String.valueOf(at.getBlockX())),
                                Placeholder.unparsed("y", String.valueOf(at.getBlockY())),
                                Placeholder.unparsed("z", String.valueOf(at.getBlockZ()))));
                    } else {
                        // Roll the speculative spawn back; the egg is intact.
                        Entity spawned = Bukkit.getEntity(entityUuid);
                        if (spawned != null) {
                            spawned.remove();
                        }
                        holder.sendMessage(messages.msg("dragon.hatch.conflict"));
                    }
                }))
                .exceptionally(t -> {
                    rollbackSpawn(entityUuid);
                    holder.sendMessage(messages.msg("common.unavailable"));
                    return null;
                });
    }

    /** Spawn spot: holder position + facing offset + lift (bounded by config). */
    private Location spawnLocationFor(@NotNull Player holder) {
        Location feet = holder.getLocation();
        Vector facing = feet.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) {
            facing = new Vector(1, 0, 0);
        }
        facing.normalize();
        return feet.clone()
                .add(facing.multiply(config.hatchOffsetForward()))
                .add(0, config.hatchOffsetUp(), 0);
    }

    private void rollbackSpawn(@NotNull UUID entityUuid) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity spawned = Bukkit.getEntity(entityUuid);
            if (spawned != null) {
                spawned.remove();
            }
        });
    }

    // ------------------------------------------------------------------
    // Call / recall
    // ------------------------------------------------------------------

    /** Typed call outcomes; the command maps each to a messages key. */
    public enum CallOutcome {
        OK, COOLDOWN, TOO_FAR, NOT_HATCHED, NOT_RESOLVED
    }

    /**
     * Recalls the owner's pet: teleports it to a hover above their head and
     * settles it on the platform. A loaded entity moves directly
     * ({@code teleportAsync}); an unloaded one is resolved through the
     * persisted location (schema v2) — its chunk loads async, the entity is
     * looked up on the next main tick, and only then does it move. A pet
     * whose chunk loads without it (removed while nobody watched) is routed
     * back to its egg through the idempotent death CAS.
     */
    public void call(@NotNull Player owner, @NotNull Consumer<CallOutcome> done) {
        Optional<Lineage> owned = hatchedOf(owner.getUniqueId());
        if (owned.isEmpty()) {
            done.accept(CallOutcome.NOT_HATCHED);
            return;
        }
        Lineage lineage = owned.get();
        long now = System.currentTimeMillis();
        Long last = lastCallUtc.get(owner.getUniqueId());
        if (last != null && now - last < config.callCooldownMs()) {
            done.accept(CallOutcome.COOLDOWN);
            return;
        }
        Location target = owner.getLocation().clone()
                .add(0, config.callHoverOffsetBlocks(), 0);
        Optional<EnderDragon> pet = livingPet(lineage);
        if (pet.isPresent()) {
            if (sameWorldAndTooFar(pet.get(), owner)) {
                done.accept(CallOutcome.TOO_FAR);
                return;
            }
            teleportAndSettle(pet.get(), target);
            lastCallUtc.put(owner.getUniqueId(), now);
            done.accept(CallOutcome.OK);
            return;
        }
        // Unloaded: resolve through the persisted snapshot (ADR-0009 + v2).
        Optional<Lineage.PersistedLocation> snapshot = lineage.locationOpt();
        UUID entityUuid = lineage.entityUuid();
        if (snapshot.isEmpty() || entityUuid == null) {
            done.accept(CallOutcome.NOT_RESOLVED);
            return;
        }
        World world = Bukkit.getWorld(snapshot.get().world());
        if (world == null) {
            done.accept(CallOutcome.NOT_RESOLVED);
            return;
        }
        world.getChunkAtAsync((int) Math.floor(snapshot.get().x()) >> 4,
                (int) Math.floor(snapshot.get().z()) >> 4, chunk -> { });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Optional<EnderDragon> resolved = dragon(entityUuid);
            if (resolved.isEmpty()) {
                // Chunk loaded, entity gone: reconcile through the death CAS.
                lineages.processDeath(lineage.lineageId(), entityUuid,
                        Optional.empty());
                done.accept(CallOutcome.NOT_RESOLVED);
                return;
            }
            teleportAndSettle(resolved.get(), target);
            lastCallUtc.put(owner.getUniqueId(), now);
            done.accept(CallOutcome.OK);
        }, 8L);
    }

    private boolean sameWorldAndTooFar(@NotNull EnderDragon pet, @NotNull Player owner) {
        if (!pet.getWorld().equals(owner.getWorld())) {
            return false; // cross-world recall is always allowed
        }
        return pet.getLocation().distance(owner.getLocation()) > config.callMaxRangeBlocks();
    }

    private void teleportAndSettle(@NotNull EnderDragon pet, @NotNull Location target) {
        pet.eject(); // never carry a rider through a recall teleport
        pet.teleportAsync(target).thenAccept(ok -> {
            if (ok) {
                Bukkit.getScheduler().runTask(plugin, () -> settle(pet));
            }
        });
    }

    // ------------------------------------------------------------------
    // Mount / dismiss
    // ------------------------------------------------------------------

    /** Typed mount outcomes. */
    public enum MountOutcome {
        OK, DISABLED, TOO_FAR, NOT_HATCHED
    }

    /**
     * Mounts the owner onto their pet (ride config gate checked first).
     * The platform state is the mounted base; the steer task takes over
     * while the rider stays aboard. Re-clicking while already aboard is an
     * idempotent OK — both the right-click interaction and the command can
     * reach this path (horse-like: right-click to ride, sneak to land).
     */
    public MountOutcome mount(@NotNull Player owner) {
        if (!config.rideEnabled()) {
            return MountOutcome.DISABLED;
        }
        Optional<Lineage> owned = hatchedOf(owner.getUniqueId());
        if (owned.isEmpty()) {
            return MountOutcome.NOT_HATCHED;
        }
        Optional<EnderDragon> pet = livingPet(owned.get());
        if (pet.isEmpty() || !pet.get().getWorld().equals(owner.getWorld())
                || pet.get().getLocation().distance(owner.getLocation()) > 24.0) {
            return MountOutcome.TOO_FAR;
        }
        EnderDragon dragon = pet.get();
        if (dragon.getPassengers().contains(owner)) {
            return MountOutcome.OK; // already in the saddle
        }
        settle(dragon);
        if (dragon.addPassenger(owner)) {
            mounted.put(dragon.getUniqueId(), owner.getUniqueId());
            return MountOutcome.OK;
        }
        return MountOutcome.TOO_FAR;
    }

    /** Dismounts any rider and settles the pet (mounted base state). */
    public void dismiss(@NotNull Player owner) {
        Optional<Lineage> owned = hatchedOf(owner.getUniqueId());
        if (owned.isEmpty()) {
            return;
        }
        livingPet(owned.get()).ifPresent(pet -> {
            pet.eject();
            settle(pet);
        });
    }

    /**
     * One closed-loop steering cycle (main thread, every
     * {@code steer-interval-ticks} while ridden): re-target the podium at
     * the rider's eyes plus look direction times the configured look-ahead,
     * and pin the control phase. E2c: the dragon flies toward the podium at
     * the measured ~16 blocks/s; per-cycle re-targeting is what closes the
     * loop on the overshoot.
     */
    public void steerTick() {
        if (mounted.isEmpty() || !config.rideEnabled()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : mounted.entrySet()) {
            Entity petEntity = Bukkit.getEntity(entry.getKey());
            Player rider = Bukkit.getPlayer(entry.getValue());
            if (!(petEntity instanceof EnderDragon pet) || !petEntity.isValid()
                    || rider == null || !pet.getPassengers().contains(rider)) {
                mounted.remove(entry.getKey());
                if (petEntity instanceof EnderDragon alive && petEntity.isValid()) {
                    settle(alive);
                }
                continue;
            }
            Location target = rider.getEyeLocation()
                    .add(rider.getLocation().getDirection()
                            .multiply(config.steerLookAheadBlocks()));
            pet.setPodium(target);
            if (pet.getPhase() != EnderDragon.Phase.FLY_TO_PORTAL) {
                pet.setPhase(EnderDragon.Phase.FLY_TO_PORTAL);
            }
        }
    }

    // ------------------------------------------------------------------
    // Fireball attack
    // ------------------------------------------------------------------

    /** Typed attack outcomes. */
    public enum AttackOutcome {
        OK, NO_TARGET, BUDGET, INTERVAL, NOT_HATCHED, FRIENDLY
    }

    /** Attack decision carrying the retry delay for budget/interval refusals. */
    public record AttackResult(@NotNull AttackOutcome outcome, long retryAfterMs) {
        static AttackResult of(@NotNull AttackOutcome outcome) {
            return new AttackResult(outcome, 0L);
        }
    }

    /**
     * Target-aware fire attack: resolves the owner's crosshair target within
     * the configured range, refuses friendly targets (owner; teammates when
     * the teams module is present), checks the rolling budget, then launches
     * a {@link DragonFireball} with the E6 recipe (direction + acceleration
     * + power + velocity — direction alone leaves it motionless). Impact
     * behavior is vanilla: no block damage (E6), cloud damage on hit; the
     * friendly-hit cancellation lives in the hit-event listener.
     */
    public AttackResult attack(@NotNull Player owner) {
        Optional<Lineage> owned = hatchedOf(owner.getUniqueId());
        if (owned.isEmpty()) {
            return AttackResult.of(AttackOutcome.NOT_HATCHED);
        }
        Optional<EnderDragon> pet = livingPet(owned.get());
        if (pet.isEmpty()) {
            return AttackResult.of(AttackOutcome.NOT_HATCHED);
        }
        Entity target = owner.getTargetEntity((int) config.attackRangeBlocks());
        if (target == null || target.getUniqueId().equals(pet.get().getUniqueId())) {
            return AttackResult.of(AttackOutcome.NO_TARGET);
        }
        if (isFriendly(owner.getUniqueId(), target)) {
            return AttackResult.of(AttackOutcome.FRIENDLY);
        }
        FireballBudget.Decision decision =
                budget.tryLaunch(owned.get().lineageId(), System.currentTimeMillis());
        if (!decision.allowed()) {
            return new AttackResult(decision.retryAfterMs() >= 10_000L
                    ? AttackOutcome.BUDGET : AttackOutcome.INTERVAL,
                    decision.retryAfterMs());
        }
        launchFireball(pet.get(), target);
        return AttackResult.of(AttackOutcome.OK);
    }

    private boolean isFriendly(@NotNull UUID owner, @NotNull Entity target) {
        if (target.getUniqueId().equals(owner)) {
            return true;
        }
        if (teams == null || !(target instanceof Player other)) {
            return false;
        }
        var ownerTeam = teams.teamOf(owner);
        var otherTeam = teams.teamOf(other.getUniqueId());
        return ownerTeam.isPresent() && ownerTeam.equals(otherTeam);
    }

    private void launchFireball(@NotNull EnderDragon pet, @NotNull Entity target) {
        Location head = pet.getLocation().clone().add(0, 2, 0);
        DragonFireball fireball = pet.getWorld().spawn(head, DragonFireball.class);
        fireballs.put(fireball.getUniqueId(),
                new Launch(pet.getUniqueId(), System.currentTimeMillis()));
        pruneFireballs();
        Vector dir = target.getLocation().toVector().subtract(head.toVector());
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(0, -1, 0);
        }
        dir.normalize();
        // E6 recipe: all four vectors, or the fireball never moves.
        fireball.setDirection(dir);
        fireball.setAcceleration(dir.clone().multiply(1.0));
        fireball.setPower(dir.clone().multiply(1.0));
        fireball.setVelocity(dir.clone().multiply(1.5));
    }

    /** Owner of a plugin-launched fireball, when it is one of ours. */
    public Optional<UUID> fireballOwner(@NotNull UUID fireballUuid, long nowUtc) {
        Launch launch = fireballs.get(fireballUuid);
        if (launch == null) {
            return Optional.empty();
        }
        // Slightly over the budget window: late hits still resolve, ancient
        // ones are treated as expired.
        if (nowUtc - launch.atUtc() > config.fireballWindowMs() * 2 + 30_000L) {
            fireballs.remove(fireballUuid);
            return Optional.empty();
        }
        return Optional.of(launch.pet());
    }

    /** Drops one fireball from the live set (hit/explosion cleanup). */
    public void forgetFireball(@NotNull UUID fireballUuid) {
        fireballs.remove(fireballUuid);
    }

    private void pruneFireballs() {
        long now = System.currentTimeMillis();
        long horizon = config.fireballWindowMs() * 2 + 30_000L;
        fireballs.entrySet().removeIf(e -> now - e.getValue().atUtc() > horizon);
    }

    /** Records an impact cloud left by one of our fireballs (hit listener). */
    public void trackCloud(@NotNull UUID cloudEntity, @NotNull UUID owner) {
        breathClouds.put(cloudEntity, new Cloud(owner, System.currentTimeMillis()));
        pruneFireballs();
        pruneBreathClouds();
    }

    /** Owner of a plugin fireball impact cloud, when it is one of ours. */
    public Optional<UUID> cloudOwner(@NotNull UUID cloudEntity) {
        Cloud cloud = breathClouds.get(cloudEntity);
        return cloud == null ? Optional.empty() : Optional.of(cloud.owner());
    }

    private void pruneBreathClouds() {
        long now = System.currentTimeMillis();
        long horizon = config.fireballWindowMs() * 2 + 30_000L;
        breathClouds.entrySet().removeIf(e -> now - e.getValue().atUtc() > horizon);
    }

    /** Fireball budget diagnostics for /dragon status. */
    public FireballBudget budgetView() {
        return budget;
    }

    // ------------------------------------------------------------------
    // Pearl feeding (interact listener entry point)
    // ------------------------------------------------------------------

    /**
     * Feeds one Ender Pearl to a living pet: validates ownership, runs the
     * cooldown-guarded heal CAS, and only after it commits consumes the
     * pearl and applies {@code heal()} (clamped by vanilla at max, E5). A
     * retried event inside the cooldown is refused by the CAS before the
     * pearl is touched, so double-heal/double-consume is structurally
     * impossible.
     */
    public void feedPearl(@NotNull Player feeder, @NotNull EnderDragon pet,
            @NotNull Lineage lineage, @NotNull ItemStack pearlStack,
            @NotNull Consumer<FeedResult> done) {
        if (!feeder.getUniqueId().equals(lineage.owner())) {
            done.accept(FeedResult.NOT_OWNER);
            return;
        }
        double healed = Math.min(pet.getHealth() + config.pearlHealAmount(),
                pet.getMaxHealth());
        lineages.pearlHeal(lineage.lineageId(), pet.getUniqueId(), healed,
                        config.pearlHealCooldownMs())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == LineageOutcome.PEARL_HEALED) {
                        pearlStack.setAmount(pearlStack.getAmount() - 1);
                        pet.heal(config.pearlHealAmount());
                        done.accept(FeedResult.OK);
                    } else if (result.outcome() == LineageOutcome.PEARL_COOLDOWN) {
                        done.accept(FeedResult.COOLDOWN);
                    } else if (result.outcome() == LineageOutcome.INVALID_STATE) {
                        done.accept(FeedResult.NOT_HATCHED);
                    } else {
                        done.accept(FeedResult.UNAVAILABLE);
                    }
                }))
                .exceptionally(t -> {
                    done.accept(FeedResult.UNAVAILABLE);
                    return null;
                });
    }

    /** Typed pearl-feed outcomes. */
    public enum FeedResult {
        OK, COOLDOWN, NOT_OWNER, NOT_HATCHED, UNAVAILABLE
    }

    /** Pearl cooldown remainder in ms for status rendering (0 = ready). */
    public long pearlCooldownRemainingMs(@NotNull Lineage lineage, long nowUtc) {
        long ready = lineage.lastPearlHealUtc() + config.pearlHealCooldownMs();
        return Math.max(0L, ready - nowUtc);
    }

    /** Call cooldown remainder in ms for status rendering (0 = ready). */
    public long callCooldownRemainingMs(@NotNull UUID owner) {
        Long last = lastCallUtc.get(owner);
        if (last == null) {
            return 0L;
        }
        return Math.max(0L, last + config.callCooldownMs() - System.currentTimeMillis());
    }

    /** Plugin handle (scheduling from command completion callbacks). */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------------
    // Persistence sweep (registered by the plugin)
    // ------------------------------------------------------------------

    /**
     * One sweep cycle (main thread, periodic + at shutdown):
     * <ol>
     *   <li>Persists a health+position snapshot for every loaded living
     *       pet (CAS on the bound entity — stale bindings drop out).</li>
     *   <li>Detects orphaned bindings: the pet's chunk is loaded (or its
     *       persisted position says so) but the entity is gone — a
     *       plugin-removal or offline wipe the remove-event never delivered.
     *       Reconciled through the idempotent death CAS into the recovery
     *       mailbox; never a silent loss (master prompt §5.6: no living
     *       dragon orphaned on unload/restart).</li>
     *   <li>Forgets fireball accounting for lineages that stopped being
     *       HATCHED (bounded memory).</li>
     * </ol>
     */
    public void sweep() {
        for (Lineage lineage : lineages.all()) {
            try {
                sweepOne(lineage);
            } catch (RuntimeException e) {
                // One bad row must never abort snapshots/orphan checks for
                // every other pet (the live NPE at sweep() proved how far a
                // single row failure reaches): log the slot and continue.
                plugin.getLogger().warning("lineage sweep failed for slot "
                        + lineage.slot() + " (" + lineage.lineageId() + "): "
                        + e.getMessage());
            }
        }
        pruneFireballs();
        pruneBreathClouds();
    }

    private void sweepOne(@NotNull Lineage lineage) {
        if (lineage.state() != net.zaminmc.rebellion.dragons.LineageState.HATCHED) {
            budget.forget(lineage.lineageId());
            // entityUuid is null for every non-HATCHED row (record
            // invariant) and ConcurrentHashMap.remove(null) throws — the
            // live NPE at PetController.sweep:599. Guarded so future states
            // that retain a binding still clean the steer set safely.
            UUID bound = lineage.entityUuid();
            if (bound != null) {
                mounted.remove(bound);
            }
            return;
        }
        UUID entityUuid = lineage.entityUuid();
        if (entityUuid == null) {
            return;
        }
        Optional<EnderDragon> pet = dragon(entityUuid);
        if (pet.isPresent()) {
            persistNow(pet.get(), lineage);
            return;
        }
        if (chunkLoadedAt(lineage)) {
            // Chunk loaded but entity missing: orphaned binding.
            lineages.processDeath(lineage.lineageId(), entityUuid, Optional.empty())
                    .thenAccept(r -> {
                        if (r.mutated()) {
                            plugin.getLogger().warning("lineage #"
                                    + lineage.slot() + " entity " + entityUuid
                                    + " vanished from a loaded chunk - returned to "
                                    + "egg and routed to the recovery mailbox");
                        }
                    });
        }
    }

    private boolean chunkLoadedAt(@NotNull Lineage lineage) {
        Optional<Lineage.PersistedLocation> loc = lineage.locationOpt();
        if (loc.isPresent()) {
            World world = Bukkit.getWorld(loc.get().world());
            if (world != null
                    && world.isChunkLoaded((int) Math.floor(loc.get().x()) >> 4,
                            (int) Math.floor(loc.get().z()) >> 4)) {
                return true;
            }
        }
        return false;
    }

    /** Persists one pet snapshot right now (after hatch/call mutations). */
    private void persistNow(@NotNull EnderDragon pet, @NotNull Lineage lineage) {
        Location loc = pet.getLocation();
        lineages.persistSnapshot(lineage.lineageId(), pet.getUniqueId(), pet.getHealth(),
                new Lineage.PersistedLocation(pet.getWorld().getName(), loc.getX(),
                        loc.getY(), loc.getZ()));
    }

    /** Status rendering helper: fireball budget used/max for one lineage. */
    public TagResolver budgetResolvers(@NotNull Lineage lineage) {
        long now = System.currentTimeMillis();
        return Placeholder.unparsed("budget",
                budget.usedInWindow(lineage.lineageId(), now) + "/"
                        + config.fireballMaxPerWindow() + " per "
                        + config.fireballWindowSeconds() + "s");
    }

    /** Forgets the controller's per-owner call cooldown (reload hygiene). */
    public void clearCooldowns() {
        lastCallUtc.clear();
    }

    /** Egg material per config (command/status rendering). */
    public Material eggMaterial() {
        Material m = Material.matchMaterial(config.eggMaterial());
        return m == null ? Material.DRAGON_EGG : m;
    }

    /** Builds a fresh, stamped, display-named egg stack for a lineage. */
    public @NotNull ItemStack buildEgg(@NotNull Lineage lineage) {
        ItemStack stack = new ItemStack(eggMaterial());
        eggs.stamp(stack, lineage);
        TagResolver slot = Placeholder.unparsed("slot",
                String.valueOf(lineage.slot()));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(messages.raw("egg.name", slot));
        meta.lore(List.of(messages.raw("egg.lore-1", slot),
                messages.raw("egg.lore-2", slot)));
        stack.setItemMeta(meta);
        return stack;
    }

    // ------------------------------------------------------------------
    // Delivery (victory mint, death transfer, claim, admin route)
    // ------------------------------------------------------------------

    /**
     * Delivery outcomes; callers render them from messages.yml.
     */
    public enum Delivery {
        DELIVERED, DROPPED, UNDELIVERABLE
    }

    /**
     * Delivers a freshly stamped egg to its new holder: inventory first,
     * protected drop at their feet when full. An offline holder is
     * undeliverable — the caller parks the lineage in the recovery mailbox
     * (the row, not the item, is the authority). The egg item is
     * indestructible by the protection listeners: damage, combustion,
     * despawn, hoppers, dispensers and portals are all cancelled for live
     * stamps (master prompt §5.6).
     */
    public Delivery deliverEgg(@NotNull UUID holder, @NotNull Lineage lineage) {
        Player player = Bukkit.getPlayer(holder);
        if (player == null || !player.isOnline()) {
            return Delivery.UNDELIVERABLE;
        }
        ItemStack egg = buildEgg(lineage);
        var leftover = player.getInventory().addItem(egg);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), egg);
            return Delivery.DROPPED;
        }
        return Delivery.DELIVERED;
    }

    /** Renders the egg display name from messages.yml (delivery notice). */
    public TagResolver[] eggResolvers(@NotNull Lineage lineage) {
        return new TagResolver[]{
                Placeholder.unparsed("slot", String.valueOf(lineage.slot()))};
    }

    /** Message catalog accessor for listeners that render inline. */
    public @NotNull MessageCatalog messages() {
        return messages;
    }

    /** Teams affiliation view (nullable: the teams module is optional). */
    public @Nullable TeamAffiliationService teamView() {
        return teams;
    }

    /** Lineage service accessor for listeners. */
    public @NotNull LineageService lineages() {
        return lineages;
    }

    /** Config accessor for listeners. */
    public @NotNull DragonsConfig config() {
        return config;
    }
}
