package net.zaminmc.rebellion.wards.detect;

import java.util.UUID;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.alert.TimedMasking;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side {@link MaskingResolver}: a player is masked when the
 * countermeasure is enabled AND they hold the configured material in the
 * main hand OR carry a live timed grant.
 *
 * <p>The resolver consults the live Player object — the sweep runs async,
 * so inventory reads race main-thread changes; that is acceptable by
 * design: masking is a countermeasure with its own natural granularity, and
 * a race can only flip one sweep's decision, never corrupt state. Timed
 * grants use the sweep's logical clock so their behavior is deterministic
 * under tests.</p>
 */
public final class BukkitMaskingResolver implements MaskingResolver {

    private final WardsConfig config;
    private final TimedMasking timed;
    private final org.bukkit.Server server;

    public BukkitMaskingResolver(WardsConfig config, TimedMasking timed,
            org.bukkit.Server server) {
        this.config = config;
        this.timed = timed;
        this.server = server;
    }

    @Override
    public boolean masked(@Nullable UUID player, long nowMs) {
        if (!config.maskingEnabled() || player == null) {
            return false;
        }
        if (timed.masked(player, nowMs)) {
            return true;
        }
        Player online = server.getPlayer(player);
        if (online == null) {
            return false;
        }
        ItemStack mainHand = online.getInventory().getItemInMainHand();
        Material material = Material.matchMaterial(config.maskingHeldMaterial());
        return material != null && mainHand != null && mainHand.getType() == material;
    }
}
