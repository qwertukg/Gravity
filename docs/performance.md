# Performance optimization results

## Result

The final optional adaptive mode reaches the mandatory 2× target at 100k and
the 3× target at 400k in calm scenes. Dense and high-speed scenes
conservatively retain the full collision budget, so their physics does not
regress in exchange for benchmark speed.

All measurements use asynchronous GPU timestamp queries, an RTX 2060 SUPER,
3440 × 1440 viewport, 512 × 256 gravity mesh, seed 20260730, 120 warm-up
frames, and 240 measured frames. Every run collected 240/240 frames with zero
dropped query slots.

| Scene | Baseline physics, ms | Final physics, ms | Physics speedup | Baseline frame, ms | Final frame, ms | End substeps |
|---|---:|---:|---:|---:|---:|---:|
| calm 50k | 5.559 | 3.572 | 1.56× | 5.670 | 3.685 | 6 |
| calm 100k | 10.656 | 5.073 | 2.10× | 10.827 | 5.245 | 4 |
| calm 400k | 56.800 | 18.911 | 3.00× | 57.339 | 19.449 | 2 |
| square 400k | 86.484 | 85.338 | 1.01× | 86.710 | 85.567 | 16 |
| projectile 100k | 10.995 | 10.877 | 1.01× | 14.460 | 14.439 | 16 |
| projectile 400k | 59.306 | 57.902 | 1.02× | 63.688 | 62.326 | 16 |

The 400k calm physics ratio is 3.003× before rounding. No scene regresses.
The requested 3–6× range is reached for the largest calm workload, but not
claimed for dense or projectile scenes because their measured physical
criteria correctly require all 16 substeps.

Run the complete matrix with:

```powershell
.\scripts\run-performance-final.ps1
```

## What changed

### Asynchronous GPU profiling

The profiler owns a 16-frame ring of timestamp queries and checks availability
before reading results. It never waits for a query in the simulation loop.
Repeated collision stages are summed per frame. It reports collision clear,
build, count, solve, total configured substeps, adaptive selection, gravity
grid, each FFT/convolution phase, gravity kick and near correction, trajectory,
render, physics, and complete frame time.

The original measurements and bottleneck assessment are in
[`performance-baseline.md`](performance-baseline.md).

### Shared-memory FFT

Each power-of-two transform line is loaded into a 16 KiB shared array. All
radix-2 stages execute with workgroup barriers, and the completed line is
written once. Bit reversal is folded into the load. Every supported rectangular
dimension from 128 through 2,048 fits one workgroup; X and Y each need one
dispatch.

Forward plus both inverse transforms improved by 3.19–4.07×. GPU validation
against the double-precision/JTransforms reference bounds relative spectrum
error below `6.4e-8`. Full details are in
[`performance-fft.md`](performance-fft.md).

### Collision traversal

The silent 64-particle-per-cell limit was removed. Contact counting now visits
each unordered pair once and atomically increments both integer counts, while
the solver keeps the original symmetric Jacobi formulas. This gives a modest
2.8–3.1% improvement at 400k.

Full-grid CSR, sparse CSR, radix-like scatter, cell-centric shared-memory, and
hybrid dense-cell variants were implemented and benchmarked. They all
regressed because the roughly 3.9 million-cell collision grid is sparse and is
rebuilt 17 times per physics step. Per the requirement to revert slower
optimizations, none was retained. Measurements and the structural explanation
are in [`performance-collisions.md`](performance-collisions.md).

### GPU-adaptive collision substeps

`--adaptive-collisions=true` enables a GPU-only policy; fixed mode remains the
default and `--collisions` remains the hard maximum.

At the start of a step:

1. A reduction obtains maximum body speed using monotonic integer `atomicMax`
   on non-negative float bit patterns.
2. The previous final pass supplies maximum penetration and contact-graph
   density.
3. A one-thread finalize shader computes:

   `ceil(maxSpeed * dt / (0.25 * diameter))`

   and:

   `ceil(maxPenetration / (0.125 * diameter))`.

4. At least 1% of bodies (minimum 256) having four or more contacts selects the
   full Jacobi budget. This protects dense clusters independently of speed.
5. The first step also uses the full budget. The result is clamped to
   `1..--collisions` and adjusted to preserve ping-pong parity.
6. The shader writes indirect dispatch commands. Inactive iterations execute
   one no-op workgroup, so there is no zero-dispatch driver edge case and no CPU
   readback.

The 0.25-diameter displacement is conservative: even the two-substep 400k
calm endpoint moves at most 0.184 diameter per selected substep. The
one-eighth-diameter penetration trigger is tighter than that displacement
bound. A high-speed projectile selects 16 immediately, and dense square keeps
16 due to contact density.

Adaptive selection itself costs 0.009 ms at 50k, 0.011 ms at 100k, and
0.024 ms at 400k.

## Physics quality

The table compares the original baseline with final fixed-seed diagnostics.
Areas are in px² and diameter/speed in px or px/s.

| Scene | Covariance area baseline → final | Equivalent diameter baseline → final | Core 90% baseline → final | Core 99% baseline → final |
|---|---:|---:|---:|---:|
| calm 50k | 5,110,750 → 5,111,332 | 2,550.9 → 2,551.1 | 8,947,290 → 8,967,320 | 10,017,212 → 10,014,535 |
| calm 100k | 5,144,434 → 5,143,949 | 2,559.3 → 2,559.2 | 8,986,848 → 8,995,883 | 9,959,764 → 9,941,125 |
| calm 400k | 5,179,402 → 5,178,623 | 2,568.0 → 2,567.8 | 8,977,082 → 9,003,763 | 9,977,102 → 10,004,605 |
| square 400k | 412,843 → 412,736 | 725.0 → 724.9 | 416,715 → 416,683 | 421,894 → 421,990 |

The largest calm area difference is 0.30%; dense-square covariance area differs
by 0.026%, equivalent diameter by 0.014%, and core 90% by 0.008%. The final
calm endpoints report zero NaN/Infinity bodies. Final maximum penetration is
0.00184 px at 50k, 0.00025 px at 100k, and 0.06131 px at 400k. Dense square
uses all 16 passes; its endpoint maximum penetration is 0.19259 px.

Projectile endpoints also retain 16 passes. Their final maximum penetration is
0.15910 px at 100k and 0.15137 px at 400k. No body count, FFT grid, search
radius, contact, near-gravity correction, precision, or collision formula was
removed or reduced.

## Correctness tests

The complete old and new test suite passes. It includes:

- JTransforms forward/inverse FFT comparison for square and rectangular grids;
- direct periodic convolution comparison;
- deterministic initialization for equal seeds;
- an independent O(N²) CPU collision detector compared with grid candidates;
- 130 bodies in one collision cell, proving there is no 64-body truncation;
- coincident bodies and finite antisymmetric `fallbackNormal`;
- contacts through opposite torus edges and fast seam crossing;
- dense square correction symmetry and penetration statistics;
- empty and singleton cells;
- projectile geometry and toroidal collision-grid coverage.

Normal execution has no blocking adaptive readback. The end-of-benchmark
adaptive and cluster diagnostics are explicitly blocking only after all timer
samples have been collected.
