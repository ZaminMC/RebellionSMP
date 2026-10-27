package net.zaminmc.rebellion.wards.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;
import org.junit.jupiter.api.Test;

/**
 * {@link WardsPlaceholders#nearestOwnWard} — the own-network-only intel
 * rule: active real wards of the viewer's network in the viewer's world;
 * decoys and inactive wards never feed the placeholder, and other worlds
 * never match.
 */
class WardsPlaceholdersTest {

    private static final String WORLD = "world";

    private Ward ward(String name, String world, double x, double z, boolean active,
            WardType type) {
        return new Ward(UUID.randomUUID(), name, UUID.randomUUID(), Optional.empty(),
                world, x, 64, z, 64, type, active, 0L);
    }

    @Test
    void nearestActiveRealWardWins() {
        Ward near = ward("near", WORLD, 100, 100, true, WardType.WARD);
        Ward far = ward("far", WORLD, 900, 900, true, WardType.WARD);
        var nearest = WardsPlaceholders.nearestOwnWard(List.of(far, near), WORLD, 120, 100);
        assertEquals(20.0, nearest.orElseThrow(), 1e-9);
    }

    @Test
    void decoysAndInactiveWardsNeverFeedIntel() {
        Ward decoy = ward("decoy", WORLD, 10, 10, true, WardType.DECOY);
        Ward inactive = ward("sleeping", WORLD, 20, 20, false, WardType.WARD);
        Ward real = ward("real", WORLD, 300, 300, true, WardType.WARD);
        var nearest = WardsPlaceholders.nearestOwnWard(
                List.of(decoy, inactive, real), WORLD, 0, 0);
        // 300*sqrt(2) -> the real ward, not the closer decoy/inactive ones.
        assertEquals(300 * Math.sqrt(2), nearest.orElseThrow(), 1e-9);
    }

    @Test
    void otherWorldsAndEmptyNetworksResolveEmpty() {
        Ward elsewhere = ward("elsewhere", "world_nether", 0, 0, true, WardType.WARD);
        assertTrue(WardsPlaceholders.nearestOwnWard(List.of(elsewhere), WORLD, 0, 0).isEmpty());
        assertTrue(WardsPlaceholders.nearestOwnWard(List.of(), WORLD, 0, 0).isEmpty());
    }

    @Test
    void exactlyOnTheAnchorIsZero() {
        Ward ward = ward("here", WORLD, 64.5, 64.5, true, WardType.WARD);
        assertEquals(0.0, WardsPlaceholders.nearestOwnWard(List.of(ward), WORLD, 64.5, 64.5)
                .orElseThrow(), 1e-9);
    }
}
