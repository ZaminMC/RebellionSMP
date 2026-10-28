package net.zaminmc.rebellion.artifacts.flow;

import org.bukkit.event.entity.EntityRemoveEvent;
import java.util.UUID;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactNames;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactState;
import net.zaminmc.rebellion.artifacts.event.RebellionObserver;
import net.zaminmc.rebellion.artifacts.item.ArtifactItems;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Physical artifact item flows (ADR-0015 §2): every possession-changing
 * Bukkit event resolves onto an ADR-0013 CAS transition, and destruction
 * means LOST (recoverable), not protection — the deliberate opposite of
 * the dragons egg policy, because artifacts are infinite-cycle assets
 * with a return pipeline. Chest storage is legal (the row stays HELD; a
 * thief's take is a transfer). Dropped items stay stealable. Duplicate
 * and racing events are benign: the CAS is the only authority.
 *
 * <p>Cancelled paths: hopper suction (an inventory-sucked item must not
 * be misread as destruction) and crafting with a live-stamped ingredient
 * (silent consumption hole stays closed).</p>
 */
public final class ArtifactFlowListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;
    private final ArtifactService service;
    private final ArtifactItems items;
    private final MessageCatalog messages;
    private final RebellionObserver observer;

    public ArtifactFlowListener(@NotNull org.bukkit.plugin.Plugin plugin,
            @NotNull ArtifactService service, @NotNull ArtifactItems items,
            @NotNull MessageCatalog messages, @NotNull RebellionObserver observer) {
        this.plugin = plugin;
        this.service = service;
        this.items = items;
        this.messages = messages;
        this.observer = observer;
    }

    /** Ground pickup by a player: transfer (or found), same holder = no-op. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        resolvePossession(player, event.getItem().getItemStack());
    }

    /** Container take (chest theft, trade UI): the stack lands with a player. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        resolvePossession(player, event.getCurrentItem());
    }

    /** Item entity removed: destruction paths park the row recoverably. */
    @EventHandler(ignoreCancelled = true)
    public void onRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof org.bukkit.entity.Item item)) {
            return;
        }
        switch (event.getCause()) {
            case DESPAWN, DEATH, EXPLODE, OUT_OF_WORLD, DISCARD -> {
                // valid "lost/destroyed" gameplay (ADR-0015 §2); DISCARD
                // covers plugin discards and kill-command removals.
            }
            default -> {
                return; // UNLOAD (entity persists), PICKUP (handled above),
                        // ENTER_BLOCK (hopper - cancelled upstream), MERGE
                        // (stack persists), DROP, PLUGIN etc.
            }
        }
        ArtifactItems.StampedIdentity identity = items.read(item.getItemStack());
        if (identity == null) {
            return;
        }
        UUID instance = identity.instance();
        service.byInstance(instance).ifPresent(artifact -> {
            if (artifact.state() == ArtifactState.ORIGIN) {
                // The origin presentation is a display pair, never a ground
                // item; a stray ORIGIN-stamped item is a clone. Ignore it.
                return;
            }
            String reason = "item removed (" + event.getCause() + ")";
            service.markLost(artifact.artifactKey(), reason)
                    .thenAccept(result -> Bukkit.getScheduler().runTask(plugin,
                            () -> plugin.getLogger().fine(() ->
                                    "artifact " + artifact.artifactKey()
                                            + " parked LOST (" + reason + "): "
                                            + result.outcome())));
        });
    }

    /** Hoppers must not suck artifacts (a container hop is not a loss). */
    @EventHandler(ignoreCancelled = true)
    public void onHopperSuction(InventoryPickupItemEvent event) {
        if (items.read(event.getItem().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    /** Live-stamped artifacts cannot be crafted away. */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        if (matrix == null) {
            return;
        }
        for (ItemStack ingredient : matrix) {
            if (items.read(ingredient) != null) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendMessage(messages.msg("flow.craft-refused"));
                }
                return;
            }
        }
    }

    /**
     * The one possession resolver: a live-stamped stack landing with
     * {@code player} transitions the row (transfer from the recorded
     * holder, or found when LOST); the same recorded holder picking it up
     * changes nothing. Stale/clone stamps resolve to nothing — inert.
     */
    private void resolvePossession(@NotNull Player player, @Nullable ItemStack stack) {
        ArtifactItems.StampedIdentity identity = items.read(stack);
        if (identity == null) {
            return;
        }
        UUID instance = identity.instance();
        service.byInstance(instance).ifPresent(artifact -> {
            switch (artifact.state()) {
                case HELD -> {
                    if (player.getUniqueId().equals(artifact.holder())) {
                        return; // possession unchanged (drop + own pickup)
                    }
                    service.transfer(artifact.artifactKey(), artifact.holder(),
                                    player.getUniqueId(), instance)
                            .thenAccept(result -> Bukkit.getScheduler().runTask(
                                    plugin, () -> notifyTransfer(player, artifact,
                                            result)));
                }
                case LOST -> service.found(artifact.artifactKey(), instance,
                                player.getUniqueId())
                        .thenAccept(result -> Bukkit.getScheduler().runTask(
                                plugin, () -> notifyTransfer(player, artifact,
                                        result)));
                default -> {
                    // ORIGIN (clone of the presentation item) or
                    // RETURNING/SPENT (superseded stamp): inert by design.
                }
            }
        });
    }

    private void notifyTransfer(@NotNull Player player, @NotNull Artifact artifact,
            net.zaminmc.rebellion.artifacts.ArtifactResult result) {
        switch (result.outcome()) {
            case TRANSFERRED, FOUND -> {
                player.sendMessage(messages.msg("flow.gained",
                        Placeholder.component("artifact", ArtifactNames.shortName(
                                messages, artifact.artifactKey()))));
                observer.checkPossession(player);
            }
            default -> {
                // INSTANCE_STALE / INVALID_STATE: a race lost or a stale copy.
                // Nothing to tell the player - the physical item is inert.
            }
        }
    }
}
