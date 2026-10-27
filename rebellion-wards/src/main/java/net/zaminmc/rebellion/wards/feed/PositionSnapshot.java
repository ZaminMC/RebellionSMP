package net.zaminmc.rebellion.wards.feed;

import org.jetbrains.annotations.NotNull;

/** Latest known position of one online player (world name + coordinates). */
public record PositionSnapshot(@NotNull String world, double x, double y, double z) {

    public static PositionSnapshot of(@NotNull String world, double x, double y, double z) {
        return new PositionSnapshot(world, x, y, z);
    }
}
