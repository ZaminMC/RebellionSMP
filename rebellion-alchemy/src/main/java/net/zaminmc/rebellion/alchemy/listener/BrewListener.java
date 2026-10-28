package net.zaminmc.rebellion.alchemy.listener;

import java.util.UUID;
import net.zaminmc.rebellion.alchemy.AlchemyService;
import net.zaminmc.rebellion.alchemy.BrewingCurve;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.alchemy.controller.BrewSourceKeys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Where brewing XP comes from (ADR-0020 §1). Brewing stands are anonymous,
 * so this listener maintains the <em>loader stamp</em>: the stand block's
 * PDC carries the UUID of the last player who placed it or loaded something
 * into it. A brew completing on an unstamped stand earns nothing (stands
 * predating the plugin go quiet until someone touches them — self-healing
 * and safe).
 *
 * <p>The award itself is the idempotent ADR-0019 service call: amount =
 * {@code xp-per-brew × completed result slots}, source key =
 * {@code brew:<world>:<x>:<y>:<z>:<second-bucket>} (brewing cycles take
 * 400 ticks, so real completions never share a one-second bucket; a
 * re-delivered event inside the same second resolves to the typed no-op).
 * Fire-and-forget: the brewer may be offline; progress surfaces through
 * {@code /brewing status}, the placeholders and the audit trail.</p>
 */
public final class BrewListener implements Listener {

    private final Plugin plugin;
    private final AlchemyService service;
    private final AlchemyPlayerCache cache;
    private final BrewingCurve curve;
    private final NamespacedKey loaderKey;

    public BrewListener(Plugin plugin, AlchemyService service,
            AlchemyPlayerCache cache, BrewingCurve curve) {
        this.plugin = plugin;
        this.service = service;
        this.cache = cache;
        this.curve = curve;
        this.loaderKey = new NamespacedKey(plugin, "brew_loader");
    }

    // ------------------------------------------------------------------
    // The loader stamp
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.BREWING_STAND) {
            return;
        }
        stamp(block, event.getPlayer().getUniqueId());
    }

    /**
     * Any click that loads something into a brewing stand refreshes the
     * stamp to the clicking player: holding an item on the cursor while
     * clicking the stand's slots, or shift-clicking from one's own
     * inventory into the brewing view. The ingredient loader is the honest
     * brewer; a shared stand re-stamps to whoever last fed it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStandLoad(InventoryClickEvent event) {
        if (event.getClickedInventory() == null
                || event.getClickedInventory().getType() != InventoryType.BREWING) {
            return;
        }
        // Clicking the stand's own slots with an item on the cursor.
        boolean cursorLoad = event.getCursor() != null
                && !event.getCursor().getType().isAir();
        // Shift-clicking from the player's own inventory into the view.
        boolean shiftLoad = event.getClick().isShiftClick()
                && event.getClickedInventory() != event.getView().getTopInventory()
                && event.getCurrentItem() != null
                && !event.getCurrentItem().getType().isAir();
        if (!cursorLoad && !shiftLoad) {
            return;
        }
        if (!(event.getClickedInventory() instanceof BrewerInventory brewer)
                || !(brewer.getHolder() instanceof BrewingStand stand)) {
            return;
        }
        stamp(stand.getBlock(), event.getWhoClicked().getUniqueId());
    }

    // ------------------------------------------------------------------
    // The award
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (!(event.getContents() instanceof BrewerInventory brewer)
                || !(brewer.getHolder() instanceof BrewingStand stand)) {
            return;
        }
        UUID loader = readStamp(stand.getBlock());
        if (loader == null) {
            plugin.getLogger().fine(() -> "brew completed on unstamped stand at "
                    + stand.getX() + "," + stand.getY() + "," + stand.getZ()
                    + " - no XP until someone loads it");
            return;
        }
        int completed = 0;
        for (ItemStack result : event.getResults()) {
            if (result != null && !result.getType().isAir()) {
                completed++;
            }
        }
        if (completed == 0) {
            return;
        }
        long amount = Math.min(curve.xpPerBrew() * completed,
                AlchemyService.MAX_AWARD);
        long now = System.currentTimeMillis();
        String sourceKey = BrewSourceKeys.forBrew(
                stand.getWorld().getName(), stand.getX(), stand.getY(),
                stand.getZ(), BrewSourceKeys.bucket(now));
        service.awardBrewXp(loader, amount, sourceKey).thenAccept(award -> {
            // Cache refresh is a map swap over a bounded future — safe off
            // the main thread; a quit-evicted viewer is simply not present.
            cache.refreshProgress(loader);
            if (award.outcome() == net.zaminmc.rebellion.alchemy.AlchemyOutcome.LEVEL_UP
                    && award.detail() != null) {
                plugin.getLogger().info(() -> "brewer " + loader + " reached "
                        + award.detail());
            }
        });
    }

    // ------------------------------------------------------------------
    // Block PDC helpers
    // ------------------------------------------------------------------

    private void stamp(Block block, UUID player) {
        if (block.getState() instanceof BrewingStand stand) {
            PersistentDataContainer pdc = stand.getPersistentDataContainer();
            pdc.set(loaderKey, PersistentDataType.STRING, player.toString());
            stand.update();
        }
    }

    private @Nullable UUID readStamp(Block block) {
        if (!(block.getState() instanceof BrewingStand stand)) {
            return null;
        }
        String raw = stand.getPersistentDataContainer()
                .get(loaderKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null; // forged/garbage stamps never earn XP (fail closed)
        }
    }
}
