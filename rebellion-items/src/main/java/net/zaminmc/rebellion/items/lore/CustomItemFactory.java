package net.zaminmc.rebellion.items.lore;

import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.zaminmc.rebellion.items.identity.CustomItemSpec;
import net.zaminmc.rebellion.items.rarity.RarityRegistry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Creates {@link ItemStack}s from {@link CustomItemSpec} definitions — the
 * ONLY sanctioned path for plugin-created custom items, because every stack
 * it produces carries the versioned identity contract.
 *
 * <p>Unique items get a fresh {@code instance_id} per creation, making every
 * physical instance independently traceable (and rejectable when forged).</p>
 */
public final class CustomItemFactory {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CustomItemSpec spec;
    private final RarityRegistry rarities;
    private final AnnotateService annotator;
    private final Logger logger;

    public CustomItemFactory(CustomItemSpec spec, RarityRegistry rarities,
            AnnotateService annotator, Logger logger) {
        this.spec = spec;
        this.rarities = rarities;
        this.annotator = annotator;
        this.logger = logger;
    }

    /**
     * Builds one stack of this custom item.
     *
     * @return null (with a sanitized log) if the base material does not exist
     *         on this server version; callers must treat that as fail-closed.
     */
    public ItemStack create() {
        Material material = Material.matchMaterial(spec.material());
        if (material == null || material.isAir()) {
            logger.warning(() -> "Custom item '" + spec.id()
                    + "': base material '" + spec.material()
                    + "' does not exist on this server version; refusing to create");
            return null;
        }
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(spec.displayName()));
            if (!spec.lore().isEmpty()) {
                meta.lore(spec.lore().stream().map(MINI::deserialize).toList());
            }
            if (spec.glint()) {
                meta.setEnchantmentGlintOverride(true);
            }
        });

        // Identity contract: custom id, rarity, tier; instance id when unique.
        annotator.assignCustomIdentity(stack, spec.id(), spec.talismanTierKey());
        // Annotation stamps schema + rarity PDC and the rarity lore line.
        annotator.annotate(stack);
        if (spec.tracksInstances()) {
            annotator.assignInstanceId(stack, UUID.randomUUID());
        }
        return stack;
    }

    /** @return the definition this factory builds. */
    public CustomItemSpec spec() {
        return spec;
    }

    RarityRegistry rarities() {
        return rarities; // visible for tests
    }
}
