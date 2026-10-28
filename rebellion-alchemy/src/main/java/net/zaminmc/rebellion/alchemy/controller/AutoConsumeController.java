package net.zaminmc.rebellion.alchemy.controller;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.alchemy.AlchemyConfig;
import net.zaminmc.rebellion.alchemy.AlchemyOutcome;
import net.zaminmc.rebellion.alchemy.AlchemyService;
import net.zaminmc.rebellion.alchemy.AutoRule;
import net.zaminmc.rebellion.alchemy.AutoTrigger;
import net.zaminmc.rebellion.alchemy.StorageScope;
import net.zaminmc.rebellion.alchemy.UnlockKind;
import net.zaminmc.rebellion.alchemy.listener.DrinkListener;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World.Environment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The auto-consumption evaluator (ADR-0020 §4): one periodic pass over
 * online players' live rules in the deterministic conflict order, at most
 * one rule per trigger per cycle, every execution riding the ADR-0019
 * claim protocol — reserve (row-authoritative), then the physical
 * locate/remove/apply under the live reservation, then commit stamps the
 * cooldown authority all-or-none. Any physical failure releases the claim:
 * the safe fallback, no cooldown bought, nothing consumed.
 *
 * <p>The efficiency {@code auto-cooldown-reduction} is applied here as
 * pure math: the holder's reductions sum, floored at the operator's
 * policy minimum — convenience, never a potion bot (the floor is the
 * anti-bot contract and the row stamp is the authority).</p>
 */
public final class AutoConsumeController implements Runnable {

    private final Plugin plugin;
    private final AlchemyService service;
    private final AlchemyConfig config;
    private final AlchemyControllerConfig controllerConfig;
    private final AlchemyPlayerCache cache;
    private final Bags bags;
    private final MessageCatalog messages;

    public AutoConsumeController(@NotNull Plugin plugin, @NotNull AlchemyService service,
            @NotNull AlchemyConfig config,
            @NotNull AlchemyControllerConfig controllerConfig,
            @NotNull AlchemyPlayerCache cache, @NotNull Bags bags,
            @NotNull MessageCatalog messages) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.controllerConfig = controllerConfig;
        this.cache = cache;
        this.bags = bags;
        this.messages = messages;
    }

    /** One evaluation cycle (main thread via the scheduler). */
    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            evaluate(player);
        }
    }

    // ------------------------------------------------------------------
    // Rule selection: first match per trigger, priority order
    // ------------------------------------------------------------------

    private void evaluate(Player player) {
        java.util.List<AutoRule> rules = cache.rulesNow(player.getUniqueId());
        if (rules == null || rules.isEmpty()) {
            return;
        }
        Map<AutoTrigger, AutoRule> picks = new EnumMap<>(AutoTrigger.class);
        for (AutoRule rule : rules) {
            if (!rule.enabled() || !rule.live() || picks.containsKey(rule.trigger())) {
                continue;
            }
            if (matches(player, rule)) {
                picks.put(rule.trigger(), rule);
            }
        }
        picks.values().forEach(rule -> attempt(player, rule));
    }

    /** @return whether the rule's trigger condition holds for the player. */
    private boolean matches(Player player, AutoRule rule) {
        return switch (rule.trigger()) {
            case HEALTH_BELOW -> AutoTriggers.healthBelow(player.getHealth(),
                    player.getMaxHealth(), rule.threshold() == null ? 0
                            : rule.threshold());
            case BURNING -> AutoTriggers.burning(player.getFireTicks());
            case NETHER_ENTRY -> AutoTriggers.inNether(
                    player.getWorld().getEnvironment());
            case FALLING -> AutoTriggers.falling(player.getFallDistance(),
                    player.isOnGround(), controllerConfig.fallThresholdBlocks());
        };
    }

    // ------------------------------------------------------------------
    // Execution: reserve -> physical -> commit
    // ------------------------------------------------------------------

    /** One located potion: what to apply, and how to remove exactly it. */
    private record PotionSource(ItemStack potion, Removal removal) {
    }

    /** Exact removal of the located potion; re-verifies before removing. */
    private interface Removal {
        /** @return true when the removal really happened. */
        boolean run();
    }

    private void attempt(Player player, AutoRule rule) {
        UUID self = player.getUniqueId();
        long now = System.currentTimeMillis();
        // Fast local check with the efficiency-reduced effective cooldown;
        // the reserve call below re-checks against the row authority.
        long effectiveSeconds = effectiveCooldown(self, rule);
        if (rule.lastFiredUtc() != null
                && rule.lastFiredUtc() + effectiveSeconds * 1000L > now) {
            return;
        }
        PotionType type = parseType(rule.potionType());
        if (type == null) {
            return;
        }
        PotionSource source = locate(player, rule.storage(), type);
        if (source == null) {
            return; // nothing to drink: silent (debug rows live in the audit)
        }
        String digest = "potion:" + rule.potionType().toLowerCase() + ":1:"
                + rule.storage().name().toLowerCase();
        service.reserveAutoConsume(self, rule.ruleId(), digest)
                .thenAccept(reserved -> Bukkit.getScheduler().runTask(plugin,
                        () -> drive(player, rule, type, source, reserved)));
    }

    private void drive(Player player, AutoRule rule, PotionType type,
            PotionSource source, net.zaminmc.rebellion.alchemy.AlchemyResult<
                    net.zaminmc.rebellion.alchemy.AlchemyClaim> reserved) {
        UUID self = player.getUniqueId();
        if (reserved.outcome() != AlchemyOutcome.AUTO_RESERVED
                || reserved.payload() == null) {
            return; // typed refusal (cooldown raced, rule toggled, ...): silent
        }
        var claim = reserved.payload();
        if (!source.removal().run()) {
            // The potion vanished between evaluation and removal — the
            // §5.7 "inventory changes between rule evaluation and
            // execution" case: release, no cooldown bought, nothing lost.
            service.releaseReservation(claim.claimId(), self,
                    "potion vanished mid-execution");
            return;
        }
        Set<String> owned = cache.unlocksNow(self);
        Potions.apply(player, source.potion(),
                owned == null ? null : DrinkListener.matching(config, owned, type));
        service.commitAutoConsume(claim.claimId(), self).thenAccept(committed -> {
            cache.refreshRules(self);
            if (controllerConfig.autoConsumeNotify()
                    && committed.outcome() == AlchemyOutcome.AUTO_CONSUMED) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        messages.msg("auto.fired",
                                Placeholder.unparsed("trigger", rule.trigger().name()
                                        .toLowerCase(java.util.Locale.ROOT)),
                                Placeholder.unparsed("potion", rule.potionType()),
                                Placeholder.unparsed("seconds",
                                        String.valueOf(effectiveCooldown(self, rule))))));
            }
        });
    }

    // ------------------------------------------------------------------
    // Location: the storage requirement, bags first for ANY
    // ------------------------------------------------------------------

    /** @return the located potion in the rule's storage scope, or null. */
    private @Nullable PotionSource locate(Player player, StorageScope scope,
            PotionType type) {
        if (scope == StorageScope.BAG || scope == StorageScope.ANY) {
            PotionSource inBag = locateInBag(player, type);
            if (inBag != null) {
                return inBag;
            }
            if (scope == StorageScope.BAG) {
                return null;
            }
        }
        return locateInInventory(player, type);
    }

    private @Nullable PotionSource locateInBag(Player player, PotionType type) {
        PlayerInventory inventory = player.getInventory();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (bags.isBag(stack)) {
                var located = bags.findPotion(stack, type);
                if (located.isPresent()) {
                    ItemStack potion = located.get().potion();
                    Removal removal = () -> bags.removeOnePotion(stack, type)
                            .isPresent();
                    return new PotionSource(potion, removal);
                }
            }
        }
        return null;
    }

    private @Nullable PotionSource locateInInventory(Player player, PotionType type) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (Potions.isOfType(stack, type)) {
                return inventorySource(inventory, slot, stack, type);
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (Potions.isOfType(offhand, type)) {
            Removal removal = () -> {
                ItemStack current = inventory.getItemInOffHand();
                if (!Potions.isOfType(current, type)) {
                    return false;
                }
                decrement(inventory::setItemInOffHand, current);
                return true;
            };
            return new PotionSource(offhand, removal);
        }
        return null;
    }

    private @NotNull PotionSource inventorySource(PlayerInventory inventory,
            int slot, ItemStack stack, PotionType type) {
        Removal removal = () -> {
            ItemStack current = inventory.getItem(slot);
            if (!Potions.isOfType(current, type)) {
                return false; // the slot changed under us: refuse, release
            }
            decrement(toStack -> inventory.setItem(slot, toStack), current);
            return true;
        };
        return new PotionSource(stack, removal);
    }

    private interface SlotSetter {
        void set(@Nullable ItemStack stack);
    }

    private void decrement(SlotSetter setter, ItemStack stack) {
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
            setter.set(stack);
        } else {
            setter.set(null);
        }
    }

    // ------------------------------------------------------------------
    // Cooldown math
    // ------------------------------------------------------------------

    /**
     * The rule's effective cooldown in seconds: the row's value minus the
     * holder's summed efficiency reductions, floored at the operator's
     * policy minimum.
     */
    private int effectiveCooldown(UUID player, AutoRule rule) {
        int reduction = 0;
        Set<String> owned = cache.unlocksNow(player);
        if (owned != null) {
            for (String key : owned) {
                var definition = config.unlocks().get(key);
                if (definition != null && definition.kind() == UnlockKind.EFFICIENCY) {
                    reduction += definition.autoCooldownReduction();
                }
            }
        }
        return Math.max(config.autoPolicy().minCooldownSeconds(),
                rule.cooldownSeconds() - reduction);
    }

    private @Nullable PotionType parseType(String raw) {
        try {
            return PotionType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null; // rule rows are validated at define time; fail closed
        }
    }
}
