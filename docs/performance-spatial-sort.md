# Spatial reordering and leaner collision passes

This stage keeps every physical rule of the fixed-substep pipeline: 16
drift/contact substeps plus the final zero-drift pass, the full contact set,
the same correction formulas, float32 and the same gravity mesh. It changes
only where data lives in memory and how many passes touch it.

## Diagnosis

Profiles of the previous default showed that the collision and near-gravity
passes were bound by cache misses, not arithmetic. Bodies were stored in
random order (scenarios shuffle their lattices, the square test shuffles its
pixels), so neighbouring GPU threads read unrelated addresses. On a
3440 × 1440 screen the collision grid heads alone take 15.6 MB and 400k bodies
6.4 MB, against a 4 MB L2 on the RTX 2060 SUPER. At an equal body count and
an equal rebuild rate, clearing and building the grid cost 1.6 ms per step in
the square test (its heads fit L2) but 11.9 ms with a screen-wide projectile
scene.

## Changes

1. **Spatial reordering** (`--sort-interval`, default every second step).
   Bodies are permuted along a Morton curve of about 6.7 × 5.6 px tiles: count
   per tile, a two-level 1,024-value scan, then a scatter that copies
   positions, velocities and accelerations bit for bit into the free ping-pong
   buffers. The scatter counts each tile back to zero, so the counts are never
   cleared. Cached neighbor lists name old IDs and are invalidated; pressures
   need no permutation because the final pass of the same step rewrites them.
   The reordering costs 0.02–0.15 ms per sorted step.
2. **No grid clearing pass.** Collision heads store
   `(epoch << idBits) | (id + 1)`. A head from an older build reads as an
   empty list. When the epoch would wrap (every 8,191 passes at capacity
   420,000) the heads are cleared once and the cache is invalidated.
3. **One load per visited body.** The build writes a 16-byte node holding the
   position and the next body. A traversal of a grid built in the same pass
   reads that node instead of `next` plus two body loads; the body's own
   position stays in a register. The pair is still evaluated in ascending ID
   order with `precise`, so the floats and the contact threshold are
   unchanged bit for bit.
4. **No idle acceleration reads.** Substeps after the first add exactly zero
   kick; they no longer load the acceleration buffer.
5. **No one-thread dispatch per pass.** The previous pass's solve (invocation
   0, after its own outputs) schedules the next broad phase; the drift pass
   adds the population check. The cache control words moved behind the
   per-body planes so every pass reaches them within eight bindings. Placing
   the scheduling at the start of the solve instead measured 14% slower at
   50k bodies without reordering, so it runs last.
6. **No duplicate cell mass.** With unit masses a gravity cell's mass equals
   its body count; `cellMass` and its atomic are gone. `float(count)` is the
   exact aggregate mass, where the former scaled integer could differ by one
   ulp.

The optional drift/solve fusion was not done: it would change the per-pass
contract that the GPU tests replay against the reference pipeline, for an
estimated gain of about 0.5 ms at 400k bodies.

## Verification

`.\gradlew.bat test gpuTest`: 46 CPU tests and 20 GPU tests pass on
2026-10-24. Besides all previous cache/reference comparisons (exact contact
IDs, bitwise isolated bodies and pairs, overflow paths, seams, walls, adaptive
dispatch), `SpatialSortIntegrationTest` checks that:

- reordering is a bit-exact permutation of bodies and accelerations, sorted by
  tile key, and leaves every tile count at zero;
- after reordering the first pass rebuilds once and finds exactly the
  brute-force contact IDs;
- the grid epoch wrap clears and rebuilds without losing a contact;
- twelve full steps with and without reordering produce the same bodies and
  pressures bit for bit (isolated colliding pairs, uniform field, mutual
  gravity off: no float sum is reordered there).

The end-of-run cluster statistics stay within the run-to-run spread of the
previous code (which is itself nondeterministic through atomic list order):
for example square-400k covariance area 415,702 / 416,012 px² before and
415,644 / 416,226 px² after, calm-400k 5,178,756 / 5,178,535 before and
5,178,281 / 5,178,961 after. Calm-050k endpoints are identical at printed
precision. No run produced a non-finite body.

## Results

Hidden-context physics benchmark, RTX 2060 SUPER, 3440 × 1440, 512 × 256
gravity mesh, capacity 420,000, seed 20260730, 120 warm-up and 240 measured
steps. The original and the new revision ran alternately (A/B/A/B) because GPU
clocks drifted by up to 8% between runs; means are averaged over both runs,
P95 is the worse run.

| Scene | Bodies | Before, ms | After, ms | Speedup | Before worst P95 | After worst P95 |
|---|---:|---:|---:|---:|---:|---:|
| calm-050k | 50,000 | 3.092 | 2.176 | 1.42× | 3.670 | 2.399 |
| calm-100k | 100,000 | 4.764 | 2.436 | 1.96× | 6.799 | 3.342 |
| calm-400k | 400,000 | 20.839 | 5.140 | 4.05× | 27.611 | 6.215 |
| square-400k | 400,000 | 61.581 | 8.274 | 7.44× | 63.546 | 8.798 |
| projectile-100k | 106,963 | 8.676 | 4.183 | 2.07× | 9.060 | 4.690 |
| projectile-400k | 406,963 | 43.704 | 8.462 | 5.16× | 46.075 | 9.069 |
| impact | 180,026 | 4.391 | 4.231 | 1.04× | 5.050 | 4.681 |
| galaxy | 200,000 | 5.582 | 4.276 | 1.31× | 5.906 | 4.538 |

`impact` and `galaxy` are the Planetary impact and Spiral galaxy scenarios.
Their bodies already sit in a compact central region whose grid fits L2, so
they gain least.

Stages at 400k bodies, ms per step (first A/B round):

| Stage | square before | square after | projectile before | projectile after | calm before | calm after |
|---|---:|---:|---:|---:|---:|---:|
| spatial sort | — | 0.153 | — | 0.100 | — | 0.093 |
| drift + validation | 0.860 | 0.755 | 0.956 | 0.789 | 0.912 | 0.805 |
| clear | 0.712 | — | 5.634 | — | 0.911 | — |
| build | 0.844 | 0.993 | 6.224 | 2.523 | 0.994 | 0.523 |
| count | 45.730 | 3.702 | 23.197 | 3.023 | 10.567 | 1.702 |
| solve | 11.861 | 1.896 | 1.432 | 0.785 | 0.670 | 0.708 |
| gravity kick + near | 1.465 | 0.419 | 6.083 | 0.849 | 6.550 | 0.893 |

Contribution of the reordering alone (one run each, not interleaved): with
`--sort-interval=0` the other five changes give 2.56, 3.86, 15.46, 41.63,
6.77, 30.07, 4.01 and 4.87 ms for the scenes above. Reordering intervals 1, 2
and 4 were within 3% of each other; 2 was never slower than 1.

Reproduce with:

```powershell
.\scripts\run-performance-lossless.ps1
```

`collisionBenchmark` also accepts a fifth argument `cached` (skip the slow
reference pipeline) and a sixth, the reordering interval.

## Memory

At capacity 420,000: nodes 6.7 MB, spare accelerations 3.4 MB, tile counts and
offsets 1 MB. The cache keeps its 42 planes plus six control words.
