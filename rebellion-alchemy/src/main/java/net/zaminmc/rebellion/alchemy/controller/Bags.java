package net.zaminmc.rebellion.alchemy.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.alchemy.BagDefinition;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Physical potion-bag identity, contents codec and storage surgery
 * (ADR-0020 §3). A bag item is the definition's material stamped in its
 * PDC with a unique {@code bag_id} plus the definition key; the contents
 * payload is the serialized item list riding the <em>same item</em>, which
 * is the whole safety design — wherever vanilla takes the item (drops,
 * chests, keepInventory), the contents travel with it, and the open
 * inventory is only ever a transient mirror persisted back after every
 * click.
 *
 * <p>Identity/contents keys live under this module's namespace, mirroring
 * how rebellion-items and rebellion-dragons own their PDC contracts. The
 * codec is deliberately boring: {@link BukkitObjectOutputStream} over an
 * {@code ArrayList}, Base64-wrapped into a string PDC slot.</p>
 *
 * <p>Serialization is a live path (boot-smoke gated); identity reads and
 * the capacity math are pure enough to trust with the unit layer once the
 * boot smoke proves the codec on real Paper.</p>
 */
public final class Bags {

    /** PDC key of the unique bag id on a bag item. */
    public static final String BAG_ID_KEY = "bag_id";
    /** PDC key of the definition key on a bag item. */
    public static final String DEFINITION_KEY = "bag_definition";
    /** PDC key of the Base64 contents payload on a bag item. */
    public static final String CONTENTS_KEY = "bag_contents";

    public static final int MAX_SLOTS = 54;

    private final NamespacedKey bagIdKey;
    private final NamespacedKey definitionKey;
    private final NamespacedKey contentsKey;

    public Bags(@NotNull Plugin plugin) {
        this.bagIdKey = new NamespacedKey(plugin, BAG_ID_KEY);
        this.definitionKey = new NamespacedKey(plugin, DEFINITION_KEY);
        this.contentsKey = new NamespacedKey(plugin, CONTENTS_KEY);
    }

    /** Identity read from a bag item's PDC. */
    public record StampedBag(@NotNull UUID bagId, @NotNull String definitionKey) {
    }

    /** A located potion inside a bag (index is its slot in the payload). */
    public record LocatedPotion(int index, @NotNull ItemStack potion) {
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Stamps a freshly-minted bag item with its unique id and definition. */
    public void stamp(@NotNull ItemStack stack, @NotNull String definitionKey,
            @NotNull UUID bagId) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(bagIdKey, PersistentDataType.STRING, bagId.toString());
        pdc.set(this.definitionKey, PersistentDataType.STRING, definitionKey);
        stack.setItemMeta(meta);
    }

    /** Reads a bag item's stamped identity; null on anything not a bag. */
    public @Nullable StampedBag read(@Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String bagId = pdc.get(bagIdKey, PersistentDataType.STRING);
        String definition = pdc.get(definitionKey, PersistentDataType.STRING);
        if (bagId == null || definition == null) {
            return null;
        }
        try {
            return new StampedBag(UUID.fromString(bagId), definition);
        } catch (IllegalArgumentException e) {
            return null; // forged stamps never authorize (fail closed)
        }
    }

    /** @return true when the stack carries this module's bag identity. */
    public boolean isBag(@Nullable ItemStack stack) {
        return read(stack) != null;
    }

    // ------------------------------------------------------------------
    // Contents codec (PDC is the authority; the open inventory is a mirror)
    // ------------------------------------------------------------------

    /** @return the bag's contents; empty when unopened/never filled. */
    public @NotNull List<ItemStack> contents(@NotNull ItemStack bagStack) {
        if (!bagStack.hasItemMeta()) {
            return List.of();
        }
        String payload = bagStack.getItemMeta().getPersistentDataContainer()
                .get(contentsKey, PersistentDataType.STRING);
        return decode(payload);
    }

    /**
     * Persists the contents into the bag item's PDC — the authority write.
     * Called after EVERY click/drag that touches the bag inventory and at
     * close, so the crash window can only ever lose the in-flight event,
     * never duplicate content.
     */
    public void persist(@NotNull ItemStack bagStack, @NotNull List<ItemStack> items) {
        ItemMeta meta = bagStack.getItemMeta();
        meta.getPersistentDataContainer().set(contentsKey, PersistentDataType.STRING,
                encode(items));
        bagStack.setItemMeta(meta);
    }

    /** Serializes the item list; an empty list encodes as "". */
    static @NotNull String encode(@NotNull List<ItemStack> items) {
        if (items.isEmpty()) {
            return "";
        }
        try (var bytes = new ByteArrayOutputStream();
             var out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(items.size());
            for (ItemStack item : items) {
                out.writeObject(item);
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("bag contents encode failed: "
                    + e.getMessage(), e);
        }
    }

    /** Inverse of {@link #encode}; malformed payloads read as empty (fail
     *  closed — a corrupted bag never dumps its payload into the world). */
    static @NotNull List<ItemStack> decode(@Nullable String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try (var bytes = new ByteArrayInputStream(
                Base64.getDecoder().decode(payload));
             var in = new BukkitObjectInputStream(bytes)) {
            int size = in.readInt();
            if (size < 0 || size > MAX_SLOTS) {
                return List.of();
            }
            List<ItemStack> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Object read = in.readObject();
                if (read instanceof ItemStack item) {
                    items.add(item);
                } else {
                    return List.of();
                }
            }
            return List.copyOf(items);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Potion surgery (the auto-consume bag scope)
    // ------------------------------------------------------------------

    /**
     * Locates the first potion of the type inside a bag's payload.
     *
     * @return the located potion and its payload index, when present.
     */
    public @NotNull Optional<LocatedPotion> findPotion(@NotNull ItemStack bagStack,
            @NotNull PotionType type) {
        List<ItemStack> items = contents(bagStack);
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item != null && Potions.isOfType(item, type)) {
                return Optional.of(new LocatedPotion(i, item));
            }
        }
        return Optional.empty();
    }

    /**
     * Removes one matching potion from the bag's payload and persists the
     * shrink (PDC surgery — exact, no fuzzy inventory matching).
     *
     * @return the removed potion, or empty when none matched (nothing
     *         written in that case).
     */
    public @NotNull Optional<ItemStack> removeOnePotion(@NotNull ItemStack bagStack,
            @NotNull PotionType type) {
        Optional<LocatedPotion> located = findPotion(bagStack, type);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStack> items = new ArrayList<>(contents(bagStack));
        ItemStack removed = items.remove(located.get().index());
        persist(bagStack, items);
        return Optional.of(removed);
    }

    /**
     * The opener-visible slot count: the definition's capacity widened by
     * the holder's summed efficiency bonus, clamped to the global ceiling.
     */
    public static int capacityFor(@NotNull BagDefinition definition, int bonus) {
        return Math.max(1, Math.min(MAX_SLOTS, definition.capacity() + Math.max(0, bonus)));
    }
}
