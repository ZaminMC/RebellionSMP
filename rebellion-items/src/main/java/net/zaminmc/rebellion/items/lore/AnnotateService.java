package net.zaminmc.rebellion.items.lore;

import java.util.List;
import java.util.logging.Logger;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.zaminmc.rebellion.items.identity.ItemIdentity;
import net.zaminmc.rebellion.items.rarity.Rarity;
import net.zaminmc.rebellion.items.rarity.RarityRegistry;

/**
 * Applies rarity identity + lore to live {@link ItemStack}s. This is the
 * thin Bukkit adapter around the testable domain ({@link LorePlanner},
 * {@link ItemIdentity}, {@link RarityRegistry}).
 *
 * <p>Metadata safety contract (master prompt §5.4): display names, existing
 * lore (excluding our own marker lines), enchantments, custom model data,
 * damage, other plugins' PDC entries and stack amounts are preserved. Only
 * our own keys and our marker lore line change. Stacks with an unknown/newer
 * {@code rebellion:schema} are left untouched (fail closed).</p>
 */
public final class AnnotateService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final RarityRegistry registry;
    private final LorePlanner planner;
    private final NamespacedKey schemaKey;
    private final NamespacedKey rarityKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey instanceIdKey;
    private final NamespacedKey tierKey;
    private final Logger logger;

    public AnnotateService(RarityRegistry registry, LorePlanner planner,
            String pluginName, Logger logger) {
        this.registry = registry;
        this.planner = planner;
        this.schemaKey = new NamespacedKey(pluginName.toLowerCase(java.util.Locale.ROOT),
                ItemIdentity.KEY_SCHEMA);
        this.rarityKey = new NamespacedKey(pluginName.toLowerCase(java.util.Locale.ROOT),
                ItemIdentity.KEY_RARITY);
        this.itemIdKey = new NamespacedKey(pluginName.toLowerCase(java.util.Locale.ROOT),
                ItemIdentity.KEY_ITEM_ID);
        this.instanceIdKey = new NamespacedKey(pluginName.toLowerCase(java.util.Locale.ROOT),
                ItemIdentity.KEY_INSTANCE_ID);
        this.tierKey = new NamespacedKey(pluginName.toLowerCase(java.util.Locale.ROOT),
                ItemIdentity.KEY_TALISMAN_TIER);
        this.logger = logger;
    }

    /**
     * Annotates one stack in place (idempotent). Returns false when the
     * stack must be skipped: null/air, no ItemMeta, or foreign/newer schema.
     */
    public boolean annotate(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer existingSchema = pdc.get(schemaKey, PersistentDataType.INTEGER);
        if (existingSchema != null && !ItemIdentity.isAnnotatedSchema(existingSchema)) {
            // Unknown (future) schema: another version wrote this item. Never
            // rewrite data we cannot interpret; fail closed.
            logger.warning(() -> "Skipping annotation for material " + stack.getType()
                    + ": unknown item schema " + existingSchema);
            return false;
        }

        Rarity rarity = registry.of(stack.getType());
        RarityRegistry.RarityStyle style = registry.style(rarity);

        // Identity PDC (only our keys).
        pdc.set(schemaKey, PersistentDataType.INTEGER, ItemIdentity.CURRENT_SCHEMA);
        pdc.set(rarityKey, PersistentDataType.STRING, rarity.key());

        // Lore: strip previous marker lines, add the fresh styled line. All
        // other elements are preserved verbatim (displayName, enchants,
        // custom model data, other plugins' data are untouched by design).
        Component rarityLine = Component.text(LorePlanner.marker())
                .append(MINI.deserialize(style.color() + style.displayName()));
        Function<Component, String> plain = PlainTextComponentSerializer.plainText()::serialize;
        List<Component> newLore = planner.plan(
                meta.hasLore() ? meta.lore() : List.of(), plain, rarityLine);
        meta.lore(newLore.isEmpty() ? null : List.copyOf(newLore));

        return stack.setItemMeta(meta);
    }

    /** @return parsed identity of the stack, empty when absent or forged. */
    public java.util.Optional<ItemIdentity> identity(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return java.util.Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer schema = pdc.get(schemaKey, PersistentDataType.INTEGER);
        if (schema == null) {
            return java.util.Optional.empty();
        }
        return ItemIdentity.parse(schema,
                pdc.get(rarityKey, PersistentDataType.STRING),
                pdc.get(itemIdKey, PersistentDataType.STRING),
                pdc.get(instanceIdKey, PersistentDataType.STRING),
                pdc.get(tierKey, PersistentDataType.STRING));
    }

    /** Stamps a unique instance id (used only where duplication matters). */
    public void assignInstanceId(ItemStack stack, java.util.UUID instanceId) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(instanceIdKey,
                PersistentDataType.STRING, instanceId.toString());
        stack.setItemMeta(meta);
    }

    /** Stamps a custom item id + talisman tier (plugin-created items only). */
    public void assignCustomIdentity(ItemStack stack, String itemId, String talismanTierKey) {
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (itemId != null) {
            pdc.set(itemIdKey, PersistentDataType.STRING, itemId);
        }
        if (talismanTierKey != null) {
            pdc.set(tierKey, PersistentDataType.STRING, talismanTierKey);
        }
        stack.setItemMeta(meta);
    }
}
