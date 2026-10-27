package net.zaminmc.rebellion.items.identity;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Versioned PersistentDataContainer identity contract for RebellionSMP items.
 *
 * <p>Keys live under the {@code rebellion} namespace. Never identify a
 * valuable item by display name or lore — lore is presentation, this contract
 * is identity.</p>
 *
 * <h2>Key layout (schema 1)</h2>
 * <ul>
 *   <li>{@code rebellion:schema} — INTEGER, contract version; unknown future
 *       versions are read fail-closed by callers.</li>
 *   <li>{@code rebellion:rarity} — STRING, canonical {@link
 *       net.zaminmc.rebellion.items.rarity.Rarity} key; present on every
 *       annotated stack.</li>
 *   <li>{@code rebellion:item_id} — STRING, custom item id (e.g. a talisman
 *       family id); present only on plugin-created custom items.</li>
 *   <li>{@code rebellion:instance_id} — STRING, canonical UUID of one unique
 *       physical instance. Present only where duplication matters (unique
 *       assets). Unknown/forged formats must be treated as invalid by
 *       authorization paths.</li>
 *   <li>{@code rebellion:talisman_tier} — STRING, canonical {@link
 *       net.zaminmc.rebellion.items.lore.TalismanTier} key; custom items
 *       only.</li>
 * </ul>
 *
 * <p>This class is deliberately server-free: pure string/UUID parsing and
 * validation rules shared by the Bukkit-facing adapter, so forged-ID and
 * version-mismatch behavior is unit-testable.</p>
 */
public final class ItemIdentity {

    public static final int CURRENT_SCHEMA = 1;

    public static final String NAMESPACE = "rebellion";
    public static final String KEY_SCHEMA = "schema";
    public static final String KEY_RARITY = "rarity";
    public static final String KEY_ITEM_ID = "item_id";
    public static final String KEY_INSTANCE_ID = "instance_id";
    public static final String KEY_TALISMAN_TIER = "talisman_tier";

    /** Custom item ids: lowercase letters, digits, underscore, 1..64 chars. */
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_]{1,64}");

    private final int schema;
    private final String rarityKey;
    private final String itemId;
    private final UUID instanceId;
    private final String talismanTierKey;

    private ItemIdentity(int schema, String rarityKey, String itemId, UUID instanceId, String talismanTierKey) {
        this.schema = schema;
        this.rarityKey = rarityKey;
        this.itemId = itemId;
        this.instanceId = instanceId;
        this.talismanTierKey = talismanTierKey;
    }

    /**
     * Parses identity fields from their stored string/int representations.
     *
     * @return empty (fail closed) if the schema is newer than supported, the
     *         rarity key is unknown, a custom item id is malformed, or an
     *         instance id is not a canonical UUID.
     */
    public static Optional<ItemIdentity> parse(int schema, String rarityKey,
            String itemId, String instanceId, String talismanTierKey) {
        if (schema < 1 || schema > CURRENT_SCHEMA) {
            return Optional.empty();
        }
        if (rarityKey == null
                || net.zaminmc.rebellion.items.rarity.Rarity.byKey(rarityKey).isEmpty()) {
            return Optional.empty();
        }
        if (itemId != null && !ITEM_ID.matcher(itemId).matches()) {
            return Optional.empty();
        }
        UUID instance;
        if (instanceId == null) {
            instance = null;
        } else {
            // Canonical form only. UUID.fromString is lenient (accepts padded
            // groups and would normalize them), but identity comparisons must
            // be exact: a non-canonical string means the value was forged or
            // written by a different tool, so it fails closed.
            try {
                UUID parsed = UUID.fromString(instanceId);
                if (!parsed.toString().equals(instanceId)) {
                    return Optional.empty();
                }
                instance = parsed;
            } catch (IllegalArgumentException forged) {
                return Optional.empty();
            }
        }
        if (talismanTierKey != null && net.zaminmc.rebellion.items.lore.TalismanTier
                .byKey(talismanTierKey).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ItemIdentity(schema, rarityKey.toLowerCase(java.util.Locale.ROOT),
                itemId, instance, talismanTierKey == null ? null
                        : talismanTierKey.toLowerCase(java.util.Locale.ROOT)));
    }

    public int schema() {
        return schema;
    }

    public String rarityKey() {
        return rarityKey;
    }

    /** @return custom item id or null for vanilla stacks without custom identity */
    public String itemId() {
        return itemId;
    }

    /** @return unique instance UUID or null when uniqueness is not tracked */
    public UUID instanceId() {
        return instanceId;
    }

    /** @return talisman tier key or null */
    public String talismanTierKey() {
        return talismanTierKey;
    }

    /** @return true when this stack was annotated by RebellionSMP. */
    public static boolean isAnnotatedSchema(int schema) {
        return schema >= 1 && schema <= CURRENT_SCHEMA;
    }
}
