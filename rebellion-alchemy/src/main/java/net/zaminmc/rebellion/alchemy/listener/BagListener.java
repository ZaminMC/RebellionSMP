package net.zaminmc.rebellion.alchemy.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.BagDefinition;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.alchemy.controller.Bags;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The potion-bag surface and every §5.7 inventory-safety rule
 * (ADR-0020 §3). Contents live in the bag item's PDC — the open inventory
 * is a transient mirror persisted back after EVERY interaction (and at
 * close), so a crash can only lose the in-flight event, never duplicate
 * content. The guards:
 *
 * <ul>
 *   <li><strong>No nesting</strong> — any click/drag that would move a bag
 *       item into a bag inventory is cancelled (bags never contain
 *       bags).</li>
 *   <li><strong>No shift/drag/cursor dupes</strong> — all movement runs
 *       through Bukkit's own click/drag handling against the mirror;
 *       nothing clones stacks, and per-interaction persistence writes the
 *       final state back one tick later.</li>
 *   <li><strong>Hoppers/automation</strong> — bag items never move through
 *       containers ({@code InventoryMoveItemEvent} cancelled for bags:
 *       "bags don't ride hoppers", which also keeps them out of automated
 *       furnace feeds).</li>
 *   <li><strong>Crafting/smelting</strong> — a bag as a crafting
 *       ingredient blocks the result; cursor/shift insertion of a bag into
 *       a furnace view is cancelled.</li>
 *   <li><strong>Death/drop</strong> — the bag item carries its PDC
 *       wherever vanilla takes it; no listener needed.</li>
 * </ul>
 *
 * <p>Honest edge, documented in the ADR: a bag item destroyed mid-open
 * (void, cactus — the guards above only stop <em>container</em> routing)
 * loses its contents with it — the item was the storage.</p>
 */
public final class BagListener implements Listener {

    /** The open-bag holder: ties a live bag item instance to its mirror. */
    public static final class BagHolder implements InventoryHolder {
        private final UUID bagId;
        private final String definitionKey;
        private final ItemStack bagItem;
        private final Component title;
        private Inventory inventory;

        BagHolder(@NotNull UUID bagId, @NotNull String definitionKey,
                @NotNull ItemStack bagItem, @NotNull Component title) {
            this.bagId = bagId;
            this.definitionKey = definitionKey;
            this.bagItem = bagItem;
            this.title = title;
        }

        void inventory(@NotNull Inventory inventory) {
            this.inventory = inventory;
        }

        public @NotNull UUID bagId() {
            return bagId;
        }

        public @NotNull String definitionKey() {
            return definitionKey;
        }

        /** The live bag item instance (contents persist into this). */
        public @NotNull ItemStack bagItem() {
            return bagItem;
        }

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("holder not yet attached");
            }
            return inventory;
        }
    }

    private final Plugin plugin;
    private final AlchemyConfig config;
    private final AlchemyPlayerCache cache;
    private final Bags bags;
    private final MessageCatalog messages;

    public BagListener(@NotNull Plugin plugin, @NotNull AlchemyConfig config,
            @NotNull AlchemyPlayerCache cache, @NotNull Bags bags,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.bags = bags;
        this.messages = messages;
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        Bags.StampedBag stamped = bags.read(item);
        if (stamped == null) {
            return; // plain material: vanilla behavior untouched
        }
        event.setCancelled(true); // opening a bag never interacts the block
        Player player = event.getPlayer();
        BagDefinition definition = config.bags().get(stamped.definitionKey());
        if (definition == null || !definition.enabled()) {
            player.sendMessage(messages.msg("bag.unknown"));
            return;
        }
        if (definition.requiredUnlock().isPresent()) {
            java.util.Set<String> owned = cache.unlocksNow(player.getUniqueId());
            String required = definition.requiredUnlock().get();
            if (owned == null || !owned.contains(required)) {
                player.sendMessage(messages.msg("bag.locked",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                .unparsed("unlock", required)));
                return;
            }
        }
        open(player, definition, item, stamped);
    }

    private void open(Player player, BagDefinition definition, ItemStack bagItem,
            Bags.StampedBag stamped) {
        int capacity = Bags.capacityFor(definition, efficiencyBonus(player));
        BagHolder holder = new BagHolder(stamped.bagId(), stamped.definitionKey(),
                bagItem, bagTitle(definition));
        Inventory inventory = Bukkit.createInventory(holder, capacity,
                bagTitle(definition));
        holder.inventory(inventory);
        List<ItemStack> contents = bags.contents(bagItem);
        for (int i = 0; i < contents.size() && i < capacity; i++) {
            inventory.setItem(i, contents.get(i));
        }
        player.openInventory(inventory);
    }

    private @NotNull Component bagTitle(BagDefinition definition) {
        return messages.raw("bag.title",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                        .unparsed("key", definition.key()));
    }

    /** The opener's summed efficiency bag-capacity bonus. */
    private int efficiencyBonus(Player player) {
        java.util.Set<String> owned = cache.unlocksNow(player.getUniqueId());
        if (owned == null) {
            return 0;
        }
        int bonus = 0;
        for (String key : owned) {
            var definition = config.unlocks().get(key);
            if (definition != null && definition.kind() == UnlockKind.EFFICIENCY) {
                bonus += definition.bagCapacityBonus();
            }
        }
        return bonus;
    }

    // ------------------------------------------------------------------
    // Clicks: nesting ban + per-interaction persistence
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BagHolder holder) {
            boolean clickedTop = event.getClickedInventory() == top;
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();
            boolean cursorIsBag = bags.isBag(cursor);
            boolean currentIsBag = bags.isBag(current);
            // Nesting ban: a bag never enters a bag inventory.
            if (cursorIsBag && clickedTop) {
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(messages.msg("bag.nesting"));
                return;
            }
            if (currentIsBag && !clickedTop && event.getClick().isShiftClick()) {
                event.setCancelled(true);
                event.getWhoClicked().sendMessage(messages.msg("bag.nesting"));
                return;
            }
            if (cursorIsBag && event.getAction() ==
                    org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true); // no cursor-shuffling bags at all
                return;
            }
            // Every allowed interaction with the view persists next tick.
            schedulePersist(holder, event.getWhoClicked());
            return;
        }
        // Furnace insertion guard (a bag must never enter a smelter).
        guardFurnaceInsert(event);
    }

    private void guardFurnaceInsert(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        if (bags.isBag(cursor) && clicked.getType() == InventoryType.FURNACE) {
            event.setCancelled(true);
            return;
        }
        // Shift-clicking a bag while a furnace view is open routes it into
        // the input slot — block that path too.
        if (bags.isBag(current) && event.getClick().isShiftClick()
                && clicked != event.getView().getTopInventory()
                && event.getView().getTopInventory().getType() == InventoryType.FURNACE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BagHolder holder)) {
            return;
        }
        if (bags.isBag(event.getOldCursor())) {
            boolean touchesTop = event.getRawSlots().stream()
                    .anyMatch(slot -> slot < top.getSize());
            if (touchesTop) {
                event.setCancelled(true); // nesting ban on the drag path
                event.getWhoClicked().sendMessage(messages.msg("bag.nesting"));
                return;
            }
        }
        schedulePersist(holder, event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof BagHolder holder) {
            persist(holder); // the final, authoritative write
        }
    }

    // ------------------------------------------------------------------
    // Container guards: hoppers + crafting
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (bags.isBag(event.getItem())) {
            event.setCancelled(true); // bags don't ride hoppers
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (bags.isBag(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Persists the mirror into the bag item's PDC one tick later — the
     * click's final state is only visible after the event completes, and
     * the one-tick delay is the standard closing of that window. Every
     * interaction schedules its own persist; the close handler writes the
     * final state directly.
     */
    private void schedulePersist(BagHolder holder, HumanEntity viewer) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inventory = holder.getInventory();
            List<ItemStack> contents = new ArrayList<>(inventory.getSize());
            for (ItemStack stack : inventory.getContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    contents.add(stack);
                }
            }
            bags.persist(holder.bagItem(), contents);
        });
    }

    private void persist(BagHolder holder) {
        Inventory inventory = holder.getInventory();
        List<ItemStack> contents = new ArrayList<>(inventory.getSize());
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                contents.add(stack);
            }
        }
        bags.persist(holder.bagItem(), contents);
    }
}
