package net.zaminmc.rebellion.wards.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardsConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Anchor lifecycle: breaking the anchor block (or blowing it up) removes the
 * bound ward. This listener is the embodiment of the no-claim contract —
 * anchor blocks are ordinary, unprotected blocks, and destroying one is a
 * legitimate counterplay (find the sentry, break it).
 *
 * <p>Cost model: the handler material-checks first (one enum compare) and
 * only then consults the in-memory anchor map (one O(1) read); no database
 * access happens on the event path. Persistence removal runs async and is
 * idempotent — a racing double-break of the same anchor resolves to one
 * removal and one NOT_FOUND, and mirror repair cleans any stale entry.</p>
 *
 * <p>The breaker receives honest async feedback that they destroyed a named
 * ward: the intel is fair (the ward is gone), and no positions are
 * revealed. The notice renders through the module's messages.yml catalog
 * (ADR-0012); the ward name arrives as a placeholder, so it can never
 * inject MiniMessage tags.</p>
 */
public final class AnchorBreakListener implements Listener {

    private final Plugin plugin;
    private final WardService service;
    private final WardsConfig config;
    private final MessageCatalog messages;

    public AnchorBreakListener(Plugin plugin, WardService service, WardsConfig config,
            MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player breaker = event.getPlayer();
        removeIfAnchor(event.getBlock(), removed -> {
            // Persistence callback thread -> hop to main for messaging.
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    breaker.sendMessage(messages.msg("anchor.broken",
                            Placeholder.unparsed("ward", removed.name() == null ? "" : removed.name()))));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        processExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        processExplosion(event.blockList());
    }

    private void processExplosion(@NotNull List<Block> blocks) {
        // Copy before async removal: the event list is event-scope state.
        List<Block> anchors = null;
        for (Block block : blocks) {
            if (isAnchor(block)) {
                if (anchors == null) {
                    anchors = new ArrayList<>(4);
                }
                anchors.add(block);
            }
        }
        if (anchors != null) {
            for (Block block : anchors) {
                removeIfAnchor(block, removed -> { });
            }
        }
    }

    /** Starts an async removal of the ward bound to this block, when one exists. */
    private void removeIfAnchor(Block block, Consumer<Ward> onRemoved) {
        if (!isAnchor(block)) {
            return;
        }
        Ward.AnchorKey key = Ward.AnchorKey.of(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        service.removeByAnchor(key).thenAccept(result -> {
            if (result.success() && result.ward() != null) {
                onRemoved.accept(result.ward());
            }
        });
    }

    private boolean isAnchor(Block block) {
        Material anchor = Material.matchMaterial(config.anchorMaterial());
        return block.getType() == anchor;
    }
}
