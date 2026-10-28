package net.zaminmc.rebellion.dragons.egg;

import net.zaminmc.rebellion.dragons.Lineage;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Physical lineage-egg identity and delivery (ADR-0009 §4). The egg item is
 * a configured {@link org.bukkit.Material} stamped in its PDC with the
 * immutable {@code lineage} UUID plus the live {@code egg_instance} UUID.
 * The instance stamp is the anti-clone contract: every (re)issue mints a
 * fresh instance, and {@code LineageService.byEggInstance} resolves only
 * the currently-live stamp of a still-egg lineage, so stale or copied eggs
 * are structurally inert.
 *
 * <p>Identity keys live under this module's namespace — rebellion-dragons
 * compiles against RebellionAPI only, so the PDC contract is owned here,
 * mirroring how rebellion-items owns its own identity keys.</p>
 */
public final class EggItems {

    /** PDC key of the immutable lineage UUID on a physical egg. */
    public static final String LINEAGE_KEY = "lineage";
    /** PDC key of the live egg-instance stamp on a physical egg. */
    public static final String INSTANCE_KEY = "egg_instance";

    private final NamespacedKey lineageKey;
    private final NamespacedKey instanceKey;

    public EggItems(@NotNull Plugin plugin) {
        this.lineageKey = new NamespacedKey(plugin, LINEAGE_KEY);
        this.instanceKey = new NamespacedKey(plugin, INSTANCE_KEY);
    }

    /** @return this module's lineage PDC key (admin forensics/tests). */
    public @NotNull NamespacedKey lineageKey() {
        return lineageKey;
    }

    /** @return this module's egg-instance PDC key. */
    public @NotNull NamespacedKey instanceKey() {
        return instanceKey;
    }

    /**
     * Stamps a freshly-built egg stack with the lineage's identity. Only
     * ever called for a row whose {@code eggInstance} is the live stamp
     * (immediately after ISSUE/DEATH/CLAIM/ROUTE mutations).
     */
    public void stamp(@NotNull ItemStack stack, @NotNull Lineage lineage) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(lineageKey, PersistentDataType.STRING, lineage.lineageId().toString());
        pdc.set(instanceKey, PersistentDataType.STRING, lineage.eggInstance().toString());
        stack.setItemMeta(meta);
    }

    /** Reads the stamped identity of a physical egg stack, if any. */
    public @Nullable StampedIdentity read(@Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String lineage = pdc.get(lineageKey, PersistentDataType.STRING);
        String instance = pdc.get(instanceKey, PersistentDataType.STRING);
        if (lineage == null || instance == null) {
            return null;
        }
        try {
            return new StampedIdentity(java.util.UUID.fromString(lineage),
                    java.util.UUID.fromString(instance));
        } catch (IllegalArgumentException e) {
            // Forged/garbage stamps never authorize anything (fail closed).
            return null;
        }
    }

    /** PDC identity read from a physical egg. */
    public record StampedIdentity(@NotNull java.util.UUID lineageId,
            @NotNull java.util.UUID eggInstance) {
    }
}
