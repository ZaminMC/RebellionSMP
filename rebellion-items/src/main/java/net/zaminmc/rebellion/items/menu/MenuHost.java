package net.zaminmc.rebellion.items.menu;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Bukkit host for a {@link MenuSpec}: renders the inventory, implements
 * {@link MenuSubject} for a player, and applies the click-safety contract.
 *
 * <p>Click safety (master prompt §9): <em>every</em> interaction with the menu
 * inventory is cancelled — shift-click, number-key swap, double-click
 * collection, drag, cursor and off-hand loops all pass through
 * {@code InventoryClickEvent}/{@code InventoryDragEvent} and are cancelled
 * before any action runs. Requirement evaluation happens before actions.</p>
 */
public final class MenuHost implements InventoryHolder, MenuSubject {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MenuSpec spec;
    private final Map<Integer, MenuSpec.ItemSpec> bySlot;
    private final Inventory inventory;
    private final Player player;
    private final boolean consoleActionsAllowed;
    private final Logger logger;

    /** Creates the host and renders the visible items (view requirements applied). */
    public MenuHost(MenuSpec spec, Player player, boolean consoleActionsAllowed, Logger logger) {
        this.spec = spec;
        this.bySlot = spec.bySlot();
        this.player = player;
        this.consoleActionsAllowed = consoleActionsAllowed;
        this.logger = logger;
        this.inventory = Bukkit.createInventory(this, spec.size(),
                MINI.deserialize(spec.title()));

        for (Map.Entry<Integer, MenuSpec.ItemSpec> entry : bySlot.entrySet()) {
            ItemStack rendered = render(entry.getValue());
            if (rendered != null) {
                inventory.setItem(entry.getKey(), rendered);
            }
        }
    }

    /** Renders one button; returns null when view requirements are unmet (slot stays empty). */
    private ItemStack render(MenuSpec.ItemSpec item) {
        if (!MenuEvaluator.requirementsMet(item.viewRequirements(), this)) {
            return null;
        }
        Material material = Material.matchMaterial(item.material());
        if (material == null) {
            return null;
        }
        ItemStack stack = new ItemStack(material, item.amount());
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(item.displayName()));
            if (!item.lore().isEmpty()) {
                meta.lore(item.lore().stream().map(MINI::deserialize).toList());
            }
        });
        return stack;
    }

    /**
     * Called by the inventory listener for clicks inside this menu. Cancels
     * the event unconditionally, then evaluates the clicked button.
     */
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true); // decorative items are never removable
        if (!(event.getWhoClicked() instanceof Player clicked)
                || event.getClickedInventory() != inventory) {
            return;
        }
        MenuSpec.ItemSpec item = bySlot.get(event.getSlot());
        if (item == null) {
            return;
        }
        boolean isRightClick = event.isRightClick() && !event.isShiftClick();
        List<MenuSpec.Requirement> requirements = isRightClick
                ? item.rightClickRequirements() : item.leftClickRequirements();
        List<MenuSpec.Action> actions = isRightClick
                ? item.rightClickActions() : item.leftClickActions();
        MenuEvaluator.handleClick(requirements, actions, this);
    }

    /** Cancels drags touching this menu unconditionally. */
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory() == inventory) {
            event.setCancelled(true);
        }
    }

    public void open() {
        player.openInventory(inventory);
    }

    public Player player() {
        return player;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------------
    // MenuSubject
    // ------------------------------------------------------------------

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String miniMessage) {
        player.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public void playSound(String soundKey) {
        // Sounds are addressed by registry key (e.g. "ui.button.click"), never
        // by the deprecated legacy Sound constants — documented in
        // docs/DELUXEMENUS_COMPATIBILITY.md.
        String normalized = soundKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return;
        }
        org.bukkit.NamespacedKey key = normalized.contains(":")
                ? org.bukkit.NamespacedKey.fromString(normalized)
                : org.bukkit.NamespacedKey.minecraft(normalized);
        if (key == null) {
            logger.warning("Menu sound '" + soundKey + "' is not a valid key; skipped.");
            return;
        }
        Sound sound = org.bukkit.Registry.SOUNDS.get(key);
        if (sound == null) {
            logger.warning("Menu sound '" + soundKey + "' does not exist in the sound registry; skipped.");
            return;
        }
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    @Override
    public void runPlayerCommand(String command) {
        String withoutSlash = command.startsWith("/") ? command.substring(1) : command;
        player.performCommand(withoutSlash);
    }

    @Override
    public void runConsoleCommand(String command) {
        // Double gate: config flag AND per-player permission. Menu YAML is
        // admin-controlled, but this path must stay deliberate (master prompt §9).
        if (!consoleActionsAllowed) {
            logger.warning("Menu requested console action but allow-console-actions is false: "
                    + command);
            return;
        }
        if (!player.hasPermission("rebellion.items.admin.menuconsole")) {
            logger.warning(() -> "Player " + player.getName()
                    + " triggered a console menu action without menuconsole permission");
            return;
        }
        String withoutSlash = command.startsWith("/") ? command.substring(1) : command;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), withoutSlash);
    }

    @Override
    public void closeMenu() {
        player.closeInventory();
    }
}
