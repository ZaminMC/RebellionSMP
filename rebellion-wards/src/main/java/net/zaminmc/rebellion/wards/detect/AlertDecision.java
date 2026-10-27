package net.zaminmc.rebellion.wards.detect;

import java.util.UUID;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;
import org.jetbrains.annotations.NotNull;

/**
 * One alert the detector decided to deliver. Pure data — rendering,
 * recipient resolution and calibration-aware formatting happen downstream
 * in {@code AlertRouter} on the main thread.
 *
 * <p>{@code decoy} mirrors {@link Ward#type()} == {@link WardType#DECOY} so
 * the router can label decoy intel without re-deriving it.</p>
 *
 * @param ward       the detecting ward
 * @param playerId   the outsider's UUID (name resolution is delivery-time;
 *                   UUIDs are the only authority)
 * @param kind       ENTER or LEAVE
 * @param distanceBlocks horizontal distance at decision time (world units);
 *                   -1 for LEAVE (meaningless after the fact)
 * @param decoy      whether the detecting ward is a decoy
 */
public record AlertDecision(@NotNull Ward ward, @NotNull UUID playerId,
        @NotNull Kind kind, double distanceBlocks, boolean decoy) {

    public enum Kind { ENTER, LEAVE }

    public static AlertDecision enter(Ward ward, UUID playerId, double distanceBlocks) {
        return new AlertDecision(ward, playerId, Kind.ENTER, distanceBlocks,
                ward.type() == WardType.DECOY);
    }

    public static AlertDecision leave(Ward ward, UUID playerId) {
        return new AlertDecision(ward, playerId, Kind.LEAVE, -1.0,
                ward.type() == WardType.DECOY);
    }
}
