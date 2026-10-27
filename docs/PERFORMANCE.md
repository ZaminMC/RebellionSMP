# Performance — measured findings

This file records **measured** numbers for the performance-sensitive paths
(master prompt §16: "record actual findings instead of invented TPS
estimates"). Numbers are from `./gradlew :rebellion-wards:wardBenchmark`
(single-run micro-benchmarks, fixed seeds, no timing assertions in the test
suite — the benchmark is a tool, tests never gate on wall-clock).

Environment of the recorded run: OpenJDK 64-Bit Server VM 25.0.5+8-LTS,
2 vCPU container, 4 GB RAM, Linux. JVM flags `-Xms256m -Xmx256m`.
Absolute numbers shift with hardware; the *shape* (constant-time cell
lookups, O(1) event marks, no scans) is what the design guarantees.

## Ward detection pipeline (rebellion-wards)

Layout: wards spread over a 20,000 × 20,000 area, radii 16–256 blocks,
64-block index cells; 100 moving players; fixed seed 20261028.

| Measurement | 200 wards | 2,000 wards |
|---|---|---|
| Movement-event path (block compare + feed mark) | ~55 ns/event | ~46 ns/event |
| Spatial index query (one cell) | ~86 ns (0.07 candidates avg) | ~197 ns (0.67 candidates avg) |
| Full sweep slice per player (drain + query + detector) | ~273 ns | ~671 ns |
| Index insert+remove pair (ward create/destroy, mirror side) | ~7.2 µs | ~2.3 µs |

Reading of the sweep numbers: at the default 1-second sweep and the global
cap of 2,000 wards, sweeping 100 players costs ≈ 67 µs of CPU per second
(~0.007% of one core) — and the sweep runs off the main thread. The
main-thread cost is the movement handler only: at ~50 ns per block-change
move, even 10,000 meaningful moves per second across all players totals
~0.5 ms/s (~0.01% of main-thread time). Ward detection adds no other
main-thread work: no per-tick scans, no per-event database or team lookups.

Candidate sets stay bounded by construction (index test asserts the
no-miss property against a linear scan at 500 wards / 3,000 probes and
that single-cell candidates stay far below the total ward count at
realistic density).

## What is deliberately NOT claimed here

- No TPS/MSPT claims: this environment has no live Paper 26.3 server. Live
  profiling (e.g. spark when installed) is the Phase 11 hardening gate.
- No MySQL numbers: persistence integration tests run against SQLite (the
  bundled driver); the MySQL path is configuration-tested only.
- Benchmark numbers are single-session, not CI-tracked medians; they
  document the architecture's cost profile, not a service-level objective.

## Reproducing

```bash
./gradlew :rebellion-wards:wardBenchmark
```

The benchmark class (`WardBenchmark`) is deterministic: same seed, same
layout, same probe pattern. Timing methodology is plain
`System.nanoTime` loops after warmup-in-the-loop; treat it as a
microbenchmark, not a profiler.
