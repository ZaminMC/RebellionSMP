package net.zaminmc.rebellion.alchemy;

/**
 * Where an auto-consumption rule may draw its potion from (ADR-0019 §4 —
 * the master prompt's "storage requirement"): the potion bag only, the plain
 * inventory only, or either. The controller enforces presence in the chosen
 * scope at execution time; the rule row carries the scope so the requirement
 * is durable and auditable.
 */
public enum StorageScope {
    BAG,
    INVENTORY,
    ANY;

    /** Parses config/rule text; null on unknown. */
    public static StorageScope parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (StorageScope scope : values()) {
            if (scope.name().equals(trimmed)) {
                return scope;
            }
        }
        return null;
    }
}
