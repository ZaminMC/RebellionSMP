package net.zaminmc.rebellion.wards.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;
import org.junit.jupiter.api.Test;

/**
 * {@link WardSpatialIndex} correctness. The randomized property test is the
 * load-bearing one: for any ward layout, a cell query must return exactly
 * the linear-scan filter (wards whose radius covers the position), because
 * the detector treats candidates as a pre-filter and re-checks distances.
 */
class WardSpatialIndexTest {

    private static final String WORLD = "world";
    private static final String OTHER = "world_nether";

    private static Ward ward(String world, double x, double z, double radius) {
        return new Ward(UUID.randomUUID(), "w" + UUID.randomUUID().toString().substring(0, 6),
                UUID.randomUUID(), Optional.empty(), world, x, 64.0, z, radius,
                WardType.WARD, true, 0L);
    }

    @Test
    void cellQueryNeverMissesARadiusCoveredWard() {
        Random random = new Random(20261028L); // deterministic seed
        WardSpatialIndex index = new WardSpatialIndex(64);
        List<Ward> wards = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double x = (random.nextDouble() - 0.5) * 2000;   // -1000..1000
            double z = (random.nextDouble() - 0.5) * 2000;
            double radius = 16 + random.nextDouble() * 240;  // 16..256
            Ward ward = ward(WORLD, x, z, radius);
            wards.add(ward);
            index.insert(ward);
        }
        // The detector treats candidates as a pre-filter and re-checks the
        // radius itself, so the correctness property is: every ward whose
        // radius covers the probe MUST appear in the cell candidates (no
        // misses). Candidates may be a superset (same-cell wards outside the
        // radius) — that is the bounded-candidate trade, verified small below.
        int maxCandidates = 0;
        for (int i = 0; i < 3000; i++) {
            double px = (random.nextDouble() - 0.5) * 2400;
            double pz = (random.nextDouble() - 0.5) * 2400;
            Set<UUID> expected = new HashSet<>();
            for (Ward w : wards) {
                if (w.horizontalDistanceSquared(px, pz) <= w.radius() * w.radius()) {
                    expected.add(w.id());
                }
            }
            Set<UUID> candidates = new HashSet<>();
            index.query(WORLD, px, pz).forEach(w -> candidates.add(w.id()));
            for (UUID id : expected) {
                assertTrue(candidates.contains(id),
                        () -> "MISSED radius-covered ward at " + px + "," + pz);
            }
            assertTrue(candidates.size() >= expected.size());
            maxCandidates = Math.max(maxCandidates, candidates.size());
        }
        // Bounded candidates at 500 wards / 2k x 2k: a single cell holds a
        // handful of overlapping wards, never the whole 500.
        assertTrue(maxCandidates <= 500, "candidate bound sanity: " + maxCandidates);
        assertTrue(maxCandidates < 100,
                "candidate sets stay small at realistic density: " + maxCandidates);
    }

    @Test
    void worldsAreIsolated() {
        WardSpatialIndex index = new WardSpatialIndex(64);
        Ward overworld = ward(WORLD, 100, 100, 64);
        Ward nether = ward(OTHER, 100, 100, 64);
        index.insert(overworld);
        index.insert(nether);

        List<Ward> inWorld = index.query(WORLD, 100, 100);
        assertEquals(1, inWorld.size());
        assertEquals(overworld.id(), inWorld.get(0).id());
        assertEquals(1, index.query(OTHER, 100, 100).size());
        assertTrue(index.query("world_the_end", 100, 100).isEmpty());
    }

    @Test
    void removalClearsEveryCoveredCell() {
        WardSpatialIndex index = new WardSpatialIndex(64);
        Ward big = ward(WORLD, 0, 0, 256); // covers 81 cells at cell size 64
        Ward elsewhere = ward(WORLD, 5000, 5000, 32);
        index.insert(big);
        index.insert(elsewhere);
        assertEquals(2, index.size());

        assertTrue(index.remove(big.id()));
        assertFalse(index.remove(big.id()), "double remove is a harmless no-op");
        assertTrue(index.query(WORLD, 0, 0).isEmpty());
        assertTrue(index.query(WORLD, 200, 0).isEmpty(),
                "removal must clear the covered cells, not just the center");
        // The unrelated ward is untouched.
        assertEquals(1, index.query(WORLD, 5000, 5000).size());
        assertEquals(1, index.size());
    }

    @Test
    void replaceSwapsRecordInPlace() {
        WardSpatialIndex index = new WardSpatialIndex(64);
        Ward active = ward(WORLD, 100, 100, 64);
        index.insert(active);
        Ward inactive = active.withActive(false);
        index.replace(inactive);

        List<Ward> found = index.query(WORLD, 100, 100);
        assertEquals(1, found.size());
        assertFalse(found.get(0).active());
        assertEquals(active.id(), found.get(0).id());
    }

    @Test
    void queryReturnsStableSnapshotAcrossConcurrentReplace() {
        WardSpatialIndex index = new WardSpatialIndex(64);
        Ward ward = ward(WORLD, 100, 100, 64);
        index.insert(ward);
        // Hold a snapshot, then toggle; the held list must not change.
        List<Ward> snapshot = index.query(WORLD, 100, 100);
        index.replace(ward.withActive(false));
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.get(0).active(), "snapshot is frozen at query time");
        assertFalse(index.query(WORLD, 100, 100).get(0).active());
    }

    @Test
    void ownedByMatchesOwnerAndTeamFilters() {
        WardSpatialIndex index = new WardSpatialIndex(64);
        UUID owner = UUID.randomUUID();
        UUID teammate = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Ward own = new Ward(UUID.randomUUID(), "own", owner, Optional.empty(),
                WORLD, 0, 64, 0, 32, WardType.WARD, true, 0L);
        Ward team = new Ward(UUID.randomUUID(), "team", teammate, Optional.of(teamId),
                WORLD, 500, 64, 500, 32, WardType.WARD, true, 0L);
        Ward stranger = ward(WORLD, 1000, 1000, 32);
        index.insert(own);
        index.insert(team);
        index.insert(stranger);

        assertEquals(List.of(own), index.ownedBy(owner, Optional.of(teamId)).stream()
                .filter(w -> w.name().equals("own")).toList());
        assertEquals(2, index.ownedBy(owner, Optional.of(teamId)).size());
        // The teammate owns nothing; their team network is just the team ward
        // (the owner's personal ward has no team binding).
        assertEquals(1, index.ownedBy(teammate, Optional.of(teamId)).size());
        assertEquals(1, index.ownedBy(stranger.owner(), Optional.empty()).size());
    }

    @Test
    void insertIsIdempotentPerId() {
        WardSpatialIndex index = new WardSpatialIndex(64);
        Ward ward = ward(WORLD, 100, 100, 64);
        index.insert(ward);
        index.insert(ward);
        assertEquals(1, index.size());
        assertEquals(1, index.query(WORLD, 100, 100).size());
    }

    @Test
    void boundaryPositionsAreInsideAtExactRadius() {
        // The detector treats candidates as a pre-filter, so the index must
        // never MISS a ward at the exact boundary of its covered cells.
        WardSpatialIndex index = new WardSpatialIndex(64);
        Ward ward = ward(WORLD, 64, 64, 64); // radius edge lands on cell lines
        index.insert(ward);
        for (double[] probe : new double[][] {
                {0.5, 64}, {128, 64}, {64, 0.5}, {64, 128}, {0, 0}, {128, 128}}) {
            assertTrue(index.query(WORLD, probe[0], probe[1]).stream()
                            .anyMatch(w -> w.id().equals(ward.id())),
                    "boundary probe missed the ward: " + probe[0] + "," + probe[1]);
        }
    }
}
