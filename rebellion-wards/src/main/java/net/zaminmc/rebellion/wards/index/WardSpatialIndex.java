package net.zaminmc.rebellion.wards.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import net.zaminmc.rebellion.wards.Ward;
import org.jetbrains.annotations.NotNull;

/**
 * Per-world uniform-grid spatial index over wards (ADR-0007).
 *
 * <h2>Why a grid and not a scan</h2>
 * <p>Master prompt §5.2 forbids scanning every online player per movement
 * event and every ward per tick. Each ward is registered in every grid cell
 * its radius covers at insert time; a position query then reads exactly one
 * cell — O(1) map hops with a candidate set bounded by the wards overlapping
 * that cell. Writes (create/remove/toggle) are rare; reads (sweep) are
 * frequent, so cells use copy-on-write sets: sweeps iterate a stable
 * snapshot without locks.</p>
 *
 * <h2>Bounded work, by construction</h2>
 * <ul>
 *   <li>Query = one {@code ConcurrentHashMap} read per world + one cell set
 *       snapshot. No world scans, no per-ward iteration.</li>
 *   <li>Insert covers {@code (2*radius/cell + 1)^2} cells — a 256-block
 *       radius on the default 64-block grid touches 81 cells, once per
 *       create, never on any hot path.</li>
 *   <li>Cell size trades insert fan-out against candidate-set size; it is a
 *       constructor knob so the benchmark can measure both ends.</li>
 * </ul>
 *
 * <p>Thread contract: any thread. All structures are concurrent; ward
 * records are immutable and safe to share. {@code remove}/{@code replace}
 * recompute the covered cells from the stored record, so a concurrent
 * double-remove is harmless (second pass finds empty cells).</p>
 */
public final class WardSpatialIndex {

    /** world → (packed cell key → wards covering that cell). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, CopyOnWriteArraySet<Ward>>> worlds =
            new ConcurrentHashMap<>();
    /** ward id → record, for id lookups and covered-cell recomputation. */
    private final ConcurrentHashMap<UUID, Ward> byId = new ConcurrentHashMap<>();

    private final int cellSize;

    /** @param cellSize grid edge in blocks (positive). 64 balances the defaults well. */
    public WardSpatialIndex(int cellSize) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive");
        }
        this.cellSize = cellSize;
    }

    public static WardSpatialIndex withDefaultCellSize() {
        return new WardSpatialIndex(64);
    }

    /** Registers a ward in every cell its radius covers. Idempotent per id. */
    public void insert(@NotNull Ward ward) {
        var cells = worlds.computeIfAbsent(ward.world(), k -> new ConcurrentHashMap<>());
        forEachCoveredCell(ward, key -> cells
                .computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(ward));
        byId.put(ward.id(), ward);
    }

    /** Removes a ward from all covered cells; harmless when absent. */
    public boolean remove(@NotNull UUID wardId) {
        Ward ward = byId.remove(wardId);
        if (ward == null) {
            return false;
        }
        var cells = worlds.get(ward.world());
        if (cells != null) {
            forEachCoveredCell(ward, key -> cells.compute(key, (k, set) -> {
                if (set == null) {
                    return null;
                }
                set.remove(ward);
                // Atomic per bin: a concurrent insert either serialized before
                // us (set non-empty, entry kept) or after (fresh entry created).
                return set.isEmpty() ? null : set;
            }));
        }
        return true;
    }

    /** Atomic-looking replace used by toggles: swap the record, keep geometry in sync. */
    public void replace(@NotNull Ward updated) {
        remove(updated.id());
        insert(updated);
    }

    /** Snapshot of wards whose radius may cover the position (callers radius-filter). */
    public @NotNull List<Ward> query(@NotNull String world, double x, double z) {
        var cells = worlds.get(world);
        if (cells == null) {
            return List.of();
        }
        CopyOnWriteArraySet<Ward> set = cells.get(cellKey(
                (int) Math.floorDiv((long) Math.floor(x), cellSize),
                (int) Math.floorDiv((long) Math.floor(z), cellSize)));
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        // Copy: the sweep may hold this list across concurrent toggles.
        return List.copyOf(set);
    }

    public @NotNull Optional<Ward> byId(@NotNull UUID wardId) {
        return Optional.ofNullable(byId.get(wardId));
    }

    /** @return snapshot of all wards (cold path: admin lists, marker publication, reload). */
    public @NotNull List<Ward> all() {
        return List.copyOf(byId.values());
    }

    /** @return wards owned by a player or their team (cold path: /ward list). */
    public @NotNull List<Ward> ownedBy(@NotNull UUID player, @NotNull Optional<UUID> teamId) {
        List<Ward> result = new ArrayList<>();
        for (Ward ward : byId.values()) {
            if (ward.owner().equals(player)
                    || (teamId.isPresent() && ward.teamId().equals(teamId))) {
                result.add(ward);
            }
        }
        return List.copyOf(result);
    }

    /** @return live count (cold path: cap pre-checks also run inside the DB transaction). */
    public int size() {
        return byId.size();
    }

    private void forEachCoveredCell(Ward ward, java.util.function.Consumer<Long> action) {
        int minX = (int) Math.floorDiv((long) Math.floor(ward.x() - ward.radius()), cellSize);
        int maxX = (int) Math.floorDiv((long) Math.floor(ward.x() + ward.radius()), cellSize);
        int minZ = (int) Math.floorDiv((long) Math.floor(ward.z() - ward.radius()), cellSize);
        int maxZ = (int) Math.floorDiv((long) Math.floor(ward.z() + ward.radius()), cellSize);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                action.accept(cellKey(cx, cz));
            }
        }
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    static { // sanity for the packing used above
        long key = ((long) (-3) << 32) | (-7 & 0xFFFFFFFFL);
        assert (int) (key >> 32) == -3 && (int) key == -7 : "cell key packing broken";
    }
}
