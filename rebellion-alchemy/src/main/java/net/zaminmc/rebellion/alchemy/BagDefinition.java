package net.zaminmc.rebellion.alchemy;

import java.util.Optional;
import org.bukkit.Material;

/**
 * One potion-bag tier definition (ADR-0019 §5, config family
 * {@code alchemy.bags}). Bag <em>definitions</em> are operator config; bag
 * <em>contents</em> live as item PDC on the physical bag item
 * (controller phase), and every inventory-safety rule (no nesting, no
 * shift-click duplication, death/craft/drag/cursor/close handling) is a
 * live-inventory listener — this layer only pins the tier contract the
 * controller will mint against.
 *
 * @param key the config-defined bag key
 * @param enabled operator toggle
 * @param capacity 1..MAX_CAPACITY slots
 * @param material the bag item material the controller mints
 * @param requiredUnlock the unlock key gating this tier (empty = open to
 *        every player)
 */
public record BagDefinition(String key, boolean enabled, int capacity,
        Material material, Optional<String> requiredUnlock) {

    public static final int MAX_CAPACITY = 54;

    public BagDefinition {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be 1.."
                    + MAX_CAPACITY + " (got " + capacity + ")");
        }
    }
}
