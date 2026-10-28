package net.zaminmc.rebellion.alchemy.listener;

import java.util.Optional;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.UnlockDefinition;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import net.zaminmc.rebellion.alchemy.controller.AlchemyPlayerCache;
import net.zaminmc.rebellion.alchemy.controller.Potions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The POWER path at drink time (ADR-0020 §2): brewed items stay vanilla;
 * when the holder of a matching POWER unlock drinks a potion of that type,
 * the unlock rewrites the applied effect over the potion's own values.
 * MONITOR after vanilla's application — an override via
 * {@code addPotionEffect}, never a stack.
 *
 * <p>Unlock lookup goes through the join-loaded cache; a cold cache means
 * an honestly missed boost, never a blocking read in the event handler.
 * The matching rule over the definition set is pure
 * ({@link #matching}) and serverless-tested: strongest
 * {@code amplifier-bonus} first, ties broken by the higher
 * {@code duration-multiplier} — a deterministic choice when operators
 * define overlapping unlocks.</p>
 */
public final class DrinkListener implements Listener {

    private final AlchemyConfig config;
    private final AlchemyPlayerCache cache;

    public DrinkListener(AlchemyConfig config, AlchemyPlayerCache cache) {
        this.config = config;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        PotionType type = Potions.baseType(item);
        if (type == null) {
            return; // not a drinkable potion: vanilla behavior untouched
        }
        Player player = event.getPlayer();
        java.util.Set<String> owned = cache.unlocksNow(player.getUniqueId());
        if (owned == null) {
            return; // cold cache: no boost this drink, never a block
        }
        UnlockDefinition boost = matching(config, owned, type);
        if (boost == null) {
            return;
        }
        Potions.apply(player, item, boost);
    }

    /**
     * The matching POWER unlock for a potion type among the owned keys, or
     * null. Pure over the definition set: enabled POWER unlocks naming the
     * type that the player owns; strongest amplifier grant wins, ties by
     * the longer duration grant.
     */
    public static @Nullable UnlockDefinition matching(@NotNull AlchemyConfig config,
            @NotNull java.util.Set<String> ownedKeys, @NotNull PotionType type) {
        UnlockDefinition best = null;
        for (UnlockDefinition definition : config.unlocks().values()) {
            if (definition.kind() != UnlockKind.POWER || !definition.enabled()
                    || !ownedKeys.contains(definition.key())
                    || definition.potionType().isEmpty()
                    || !type.equals(definition.potionType().get())) {
                continue;
            }
            if (best == null || definition.amplifierBonus() > best.amplifierBonus()
                    || (definition.amplifierBonus() == best.amplifierBonus()
                        && definition.durationMultiplier() > best.durationMultiplier())) {
                best = definition;
            }
        }
        return best;
    }

    /** Convenience overload for tests: match by key set over a config. */
    public static @NotNull Optional<UnlockDefinition> matchingOpt(
            @NotNull AlchemyConfig config, @NotNull java.util.Set<String> ownedKeys,
            @NotNull PotionType type) {
        return Optional.ofNullable(matching(config, ownedKeys, type));
    }
}
