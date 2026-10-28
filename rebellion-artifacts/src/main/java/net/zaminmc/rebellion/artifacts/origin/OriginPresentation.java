package net.zaminmc.rebellion.artifacts.origin;

import java.util.List;
import java.util.UUID;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.artifacts.item.ArtifactItems;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The protected-origin presentation (ADR-0015 §1): while a row is ORIGIN,
 * the anchor shows a tagged ItemDisplay (visual) + Interaction (click
 * target) pair. The pair is <em>presentation, not state</em> — the row is
 * the authority — so it is fully self-healing: {@link #reconcile()} runs on
 * every sweep and guarantees, per ORIGIN row whose anchor chunk is loaded,
 * exactly one live pair near the anchor (and no pair for non-ORIGIN rows).
 * A vanished presentation (restart, a stray removal) costs nothing; a
 * duplicate is cleaned up; the discoverable origin always comes back.
 *
 * <p>Chunk discipline: presentations only reconcile in <em>loaded</em>
 * chunks (entities persist frozen in unloaded chunks anyway); spawning
 * goes through {@link World#getChunkAtAsync} so the sweep never forces a
 * synchronous load. Capture validates through the row's CAS — the entity
 * tag is only a routing hint, never an authorization.</p>
 */
public final class OriginPresentation {

    /** PDC marker key on both entities of a presentation pair. */
    static final String PRESENTATION_TAG = "origin_presentation";
    /** Scan radius (blocks) around the anchor when reconciling. */
    static final double SCAN_RADIUS = 8.0;

    private final Plugin plugin;
    private final ArtifactService service;
    private final ArtifactsConfig config;
    private final ArtifactItems items;
    private final MessageCatalog messages;
    private final NamespacedKey tagKey;

    public OriginPresentation(@NotNull Plugin plugin, @NotNull ArtifactService service,
            @NotNull ArtifactsConfig config, @NotNull ArtifactItems items,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.items = items;
        this.messages = messages;
        this.tagKey = new NamespacedKey(plugin, PRESENTATION_TAG);
    }

    /** @return the PDC tag marking presentation entities (listener/tests). */
    public @NotNull NamespacedKey tagKey() {
        return tagKey;
    }

    /**
     * Reconcile every row's presentation (called from the sweep, on the
     * main thread). For each artifact: chunk loaded at the anchor ⇒ exactly
     * one pair exists while ORIGIN, none otherwise. Async chunk loads for
     * unloaded anchors are requested so the next pass can service them.
     */
    public void reconcile() {
        for (Artifact artifact : service.all()) {
            World world = world(artifact);
            if (world == null) {
                continue; // world not imported (yet); nothing to present
            }
            var anchor = config.origin(artifact.artifactKey());
            Location location = new Location(world, anchor.x() + 0.5, anchor.y(),
                    anchor.z() + 0.5);
            if (!world.isChunkLoaded(location.getBlockX() >> 4,
                    location.getBlockZ() >> 4)) {
                // Ask for the chunk asynchronously; the next sweep services it.
                world.getChunkAtAsync(location).thenAccept(chunk ->
                        plugin.getLogger().fine(() -> "anchor chunk requested for "
                                + artifact.artifactKey()));
                continue;
            }
            boolean wantPresent = artifact.state()
                    == net.zaminmc.rebellion.artifacts.ArtifactState.ORIGIN;
            reconcileLoaded(artifact, location, wantPresent);
        }
    }

    private void reconcileLoaded(@NotNull Artifact artifact, @NotNull Location anchor,
            boolean wantPresent) {
        List<ItemDisplay> displays = new java.util.ArrayList<>(
                anchor.getNearbyEntitiesByType(ItemDisplay.class, SCAN_RADIUS));
        List<Interaction> interactions = new java.util.ArrayList<>(
                anchor.getNearbyEntitiesByType(Interaction.class, SCAN_RADIUS));
        List<ItemDisplay> ours = displays.stream()
                .filter(d -> isOurs(d, artifact.artifactKey())).toList();
        List<Interaction> oursInteraction = interactions.stream()
                .filter(i -> isOurs(i, artifact.artifactKey())).toList();

        if (!wantPresent) {
            ours.forEach(this::removeQuietly);
            oursInteraction.forEach(this::removeQuietly);
            return;
        }
        if (ours.isEmpty() || oursInteraction.isEmpty()) {
            // A half is missing (a kill or a partial teardown took it):
            // converge to exactly one fresh pair — clear every remaining
            // half first so a display-less interaction can never orphan.
            ours.forEach(this::removeQuietly);
            oursInteraction.forEach(this::removeQuietly);
            spawnPair(artifact, anchor);
            return;
        }
        // Both halves present: drop extras (duplicates cannot survive a
        // sweep — the count converges to exactly one pair).
        for (int i = 1; i < ours.size(); i++) {
            removeQuietly(ours.get(i));
        }
        for (int i = 1; i < oursInteraction.size(); i++) {
            removeQuietly(oursInteraction.get(i));
        }
    }

    /** Spawns the protected pair for an ORIGIN row at its anchor. */
    private void spawnPair(@NotNull Artifact artifact, @NotNull Location anchor) {
        World world = anchor.getWorld();
        ItemDisplay display = world.spawn(anchor, ItemDisplay.class, spawned -> {
            spawned.setItemStack(items.build(artifact, config, messages));
            spawned.setBillboard(Billboard.CENTER);
            spawned.setItemDisplayTransform(ItemDisplayTransform.FIXED);
            spawned.setPersistent(true); // survives restarts; sweep reconciles
            spawned.getPersistentDataContainer().set(tagKey,
                    PersistentDataType.STRING, artifact.artifactKey().name());
            spawned.customName(messages.raw("origin.presentation-name"));
            spawned.setCustomNameVisible(true);
        });
        world.spawn(anchor, Interaction.class, spawned -> {
            spawned.setInteractionWidth(0.9f);
            spawned.setInteractionHeight(0.9f);
            spawned.setResponsive(true);
            spawned.setPersistent(true);
            spawned.getPersistentDataContainer().set(tagKey,
                    PersistentDataType.STRING, artifact.artifactKey().name());
        });
        plugin.getLogger().fine(() -> "origin presentation spawned for "
                + artifact.artifactKey() + " (" + display.getUniqueId() + ")");
    }

    /** Removes the presentation pair around an anchor (capture success). */
    public void removePresentation(@NotNull ArtifactKey key) {
        World world = world(key);
        if (world == null) {
            return;
        }
        var anchor = config.origin(key);
        Location location = new Location(world, anchor.x() + 0.5, anchor.y(),
                anchor.z() + 0.5);
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return; // nothing loaded to remove; the sweep will not respawn ORIGIN-past rows
        }
        location.getNearbyEntitiesByType(ItemDisplay.class, SCAN_RADIUS).stream()
                .filter(d -> isOurs(d, key)).forEach(this::removeQuietly);
        location.getNearbyEntitiesByType(Interaction.class, SCAN_RADIUS).stream()
                .filter(i -> isOurs(i, key)).forEach(this::removeQuietly);
    }

    /** @return the artifact key a tagged entity routes to, else null. */
    public @Nullable ArtifactKey keyOf(@NotNull Entity entity) {
        String raw = entity.getPersistentDataContainer()
                .get(tagKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return ArtifactKey.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** @return whether this entity is a presentation of the given artifact. */
    public boolean isPresentation(@NotNull Entity entity) {
        return keyOf(entity) != null;
    }

    private boolean isOurs(@NotNull Entity entity, @NotNull ArtifactKey key) {
        String raw = entity.getPersistentDataContainer()
                .get(tagKey, PersistentDataType.STRING);
        return raw != null && raw.equals(key.name());
    }

    private void removeQuietly(@NotNull Entity entity) {
        try {
            entity.remove();
        } catch (Exception e) {
            plugin.getLogger().warning("presentation removal failed: " + e.getMessage());
        }
    }

    private @Nullable World world(@NotNull ArtifactKey key) {
        return plugin.getServer().getWorld(config.origin(key).world());
    }

    private @Nullable World world(@NotNull Artifact artifact) {
        return plugin.getServer().getWorld(artifact.originWorld());
    }
}
