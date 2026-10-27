package net.zaminmc.rebellion.wards.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.WardsConfig.AlertChannel;
import net.zaminmc.rebellion.wards.detect.AlertDecision;
import net.zaminmc.rebellion.wards.detect.DetectionState;
import net.zaminmc.rebellion.wards.detect.WardDetector;
import net.zaminmc.rebellion.wards.distance.DistanceService;
import net.zaminmc.rebellion.wards.feed.MovementFeed;
import net.zaminmc.rebellion.wards.feed.PositionSnapshot;
import net.zaminmc.rebellion.wards.index.WardSpatialIndex;

/**
 * Deterministic micro-benchmark for the pure detection pipeline (NOT a unit
 * test; no assertions, no flaky timing gates). Prints measured ns/op for:
 *
 * <ol>
 *   <li>the movement-event path (block compare + feed mark),</li>
 *   <li>the spatial-index query at realistic ward counts,</li>
 *   <li>the full per-player sweep slice (query + detector), and</li>
 *   <li>index insert/remove (ward create/destroy cost).</li>
 * </ol>
 *
 * <p>Numbers recorded in docs/PERFORMANCE.md are runs of this class on the
 * development machine, with the JVM flags from the {@code wardBenchmark}
 * Gradle task. Run: {@code ./gradlew :rebellion-wards:wardBenchmark}</p>
 */
public final class WardBenchmark {

    public static void main(String[] args) {
        System.out.println("RebellionWards pipeline micro-benchmark");
        System.out.println("JVM: " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.vm.version"));
        System.out.println();

        WardsConfig config = new WardsConfig("LODESTONE", 6, 16.0, 256.0, 3, 12,
                2000, Set.of("leader", "officer"), true, 20, 0, true,
                "AMETHYST_SHARD", AlertChannel.TEAM, 300, true,
                Map.of(), "blocks", false);
        DistanceService unused = new DistanceService(config);

        for (int wardCount : new int[] {200, 2000}) {
            benchmarkAtDensity(config, wardCount);
        }
    }

    private static void benchmarkAtDensity(WardsConfig config, int wardCount) {
        System.out.println("=== Density: " + wardCount + " wards over 20k x 20k area ===");

        // World-scale spread with fixed seed: reproducible layout.
        java.util.Random random = new java.util.Random(20261028L);
        WardSpatialIndex index = WardSpatialIndex.withDefaultCellSize();
        for (int i = 0; i < wardCount; i++) {
            double x = (random.nextDouble() - 0.5) * 20_000;
            double z = (random.nextDouble() - 0.5) * 20_000;
            double radius = 16 + random.nextDouble() * 240;
            index.insert(new Ward(UUID.randomUUID(), "w" + i, UUID.randomUUID(),
                    Optional.empty(), "world", x, 64, z, radius,
                    i % 10 == 0 ? WardType.DECOY : WardType.WARD, true, 0L));
        }

        int players = 100;
        List<UUID> ids = new ArrayList<>(players);
        MovementFeed feed = new MovementFeed();
        for (int i = 0; i < players; i++) {
            ids.add(UUID.randomUUID());
            feed.update(ids.get(i), PositionSnapshot.of("world",
                    (random.nextDouble() - 0.5) * 20_000, 64,
                    (random.nextDouble() - 0.5) * 20_000));
        }

        WardDetector detector = new WardDetector(config,
                new DetectionState(3_600_000L), null, (p, now) -> false);

        // 1. Movement-event path: same-block compare rejects; cross-block marks.
        {
            int iterations = 2_000_000;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                UUID player = ids.get(i % players);
                // Half same-block (rejected by the listener's compare, modeled
                // by comparing against itself), half real marks.
                if ((i & 1) == 0) {
                    feed.update(player, PositionSnapshot.of("world", i % 20_000, 64, i % 20_000));
                } else {
                    feed.update(player, PositionSnapshot.of("world", i % 20_000, 64, i % 20_000));
                }
            }
            long elapsed = System.nanoTime() - start;
            System.out.printf("feed mark (event path):        %,10.1f ns/op (%,d ops)%n",
                    elapsed / (double) iterations, iterations);
        }

        // 2. Index query alone.
        {
            int iterations = 1_000_000;
            long start = System.nanoTime();
            int total = 0;
            for (int i = 0; i < iterations; i++) {
                total += index.query("world", (i * 7) % 20_000 - 10_000, (i * 13) % 20_000 - 10_000)
                        .size();
            }
            long elapsed = System.nanoTime() - start;
            System.out.printf("index query:                   %,10.1f ns/op (avg candidates %.2f)%n",
                    elapsed / (double) iterations, total / (double) iterations);
        }

        // 3. Full sweep slice: drain + query + detector per changed player.
        {
            // Re-mark everyone as dirty each round to simulate movement.
            int rounds = 20_000;
            long elapsedTotal = 0;
            long alertsTotal = 0;
            for (int round = 0; round < rounds; round++) {
                for (UUID id : ids) {
                    feed.update(id, PositionSnapshot.of("world",
                            (random.nextDouble() - 0.5) * 20_000, 64,
                            (random.nextDouble() - 0.5) * 20_000));
                }
                long start = System.nanoTime();
                List<Map.Entry<UUID, PositionSnapshot>> changed = feed.drainChanged();
                List<AlertDecision> alerts = new ArrayList<>();
                for (Map.Entry<UUID, PositionSnapshot> entry : changed) {
                    PositionSnapshot snapshot = entry.getValue();
                    List<Ward> candidates = index.query("world", snapshot.x(), snapshot.z());
                    if (!candidates.isEmpty()) {
                        alerts.addAll(detector.detect(entry.getKey(), "world",
                                snapshot.x(), snapshot.z(), candidates,
                                System.currentTimeMillis()));
                    }
                }
                elapsedTotal += System.nanoTime() - start;
                alertsTotal += alerts.size();
            }
            System.out.printf("sweep slice (drain+query+detect): %,9.1f ns/player/sweep "
                            + "(%,d players x %,d rounds, %,d alerts)%n",
                    elapsedTotal / (rounds * (double) players), players, rounds, alertsTotal);
        }

        // 4. Ward create/destroy cost (index side only; DB writes are async).
        {
            int iterations = 20_000;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                Ward ward = new Ward(UUID.randomUUID(), "tmp" + i, UUID.randomUUID(),
                        Optional.empty(), "world", i * 3, 64, i * 5, 64,
                        WardType.WARD, true, 0L);
                index.insert(ward);
                index.remove(ward.id());
            }
            long elapsed = System.nanoTime() - start;
            System.out.printf("index insert+remove pair:      %,10.1f ns/op (%,d ops)%n",
                    elapsed / (double) iterations, iterations);
        }
        System.out.println();
    }

    private WardBenchmark() {
    }
}
