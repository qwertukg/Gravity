# Fixed-substep neighbor and contact cache

> Later stage: spatial reordering, epoch-tagged grid heads and four dispatches
> per pass are described in [performance-spatial-sort.md](performance-spatial-sort.md).

The default collision path now reuses broad-phase candidates and passes found
contacts directly to the solver. `--adaptive-collisions` remains **false**.
The default still executes 16 drift/contact substeps and the final zero-drift
contact pass. No particles, contact iterations, gravity interactions, FFT
resolution, pressure calculations, restitution terms, or float precision were
removed to obtain these results.

## Implementation and coverage

`CollisionNeighborCache` owns reference positions, a small GPU control buffer,
and a structure-of-arrays cache. Each body has room for 32 candidate IDs and
8 current contact IDs, plus two metadata words. These are fast-path capacities,
**not contact limits**:

- Up to 8 contacts: `solve` uses the ordered IDs found by `count`; zero contacts
  means no neighbor traversal. The original coordinate/velocity arithmetic and
  pressure write still run, including the float round trips for isolated bodies.
- More contacts: the solver visits the candidate list.
- More than 32 candidates: both passes traverse the complete retained grid.
  No list is truncated and no additional allocation is required to retain pairs.

At a rebuild, candidates are collected out to `diameter + skin`, with a float
rounding guard. Before **every** subsequent contact pass, after the kick/drift,
the GPU checks each body's actual periodic displacement from its build position.
Reuse is allowed only below half the skin minus the rounding guard. This includes
the previous Jacobi correction as well as current drift; it is not a velocity-only
prediction. Adding particles invalidates the cache. Reset creates a fresh cache.

The torus metric obeys the triangle inequality: a pair initially farther than
`D + s` cannot be within `D` when each endpoint moved less than `s/2`. Hence every
contact at the original discrete collision sample remains in the candidate set.
The expanded cell stencil also covers this reach, including the overflow path.
Periodic cells are visited once even when a stencil covers a whole small domain.
The numeric guard is 64 ULPs of the larger viewport dimension; a smaller requested
skin disables reuse conservatively.

Rebuilding expanded lists on every pass regresses fast-projectile scenes.
The GPU therefore selects the ordinary contact grid when displacement per
substep is large, or the previous pass's density samples indicate a dense scene.
Density sampling examines one body per 256-thread workgroup and selects the
direct grid if at least a quarter of samples (minimum eight) have four contacts.
Sampling changes only the choice of broad phase: **both paths enumerate every
contact**, and both retain the same integration schedule. Direct mode still
passes current contact IDs to the solver.

All decisions stay on the GPU. No frame-loop readback or float atomics were
introduced. Shader storage bindings stay within OpenGL 4.3's eight-buffer
minimum. At capacity 400,000 the extra buffers occupy about 67.1 MiB. If the
candidate buffer would exceed `GL_MAX_SHADER_STORAGE_BLOCK_SIZE`, the application
reports this and uses the complete reference linked-grid path.

## Floating-point behavior

The strict contact predicate now uses GLSL `precise` for displacement and squared
distance in **both** the reference and cached implementations. GPU testing found
that driver FMA contraction could otherwise change contact classification at the
threshold between counting and solving. This retains float32 and the same
mathematical test, while fixing that inconsistency.

Bitwise equality of arbitrary long trajectories is **not** a guarantee: atomic
linked-list insertion was already order-dependent, and retaining lists changes
the order of floating-point accumulation between rebuilds. Tests instead require
exact contact counts/IDs against independent enumeration, bitwise results for
isolated bodies and simple pairs, and bounded one-pass float rounding for
multi-contact corrections and pressure. No extra force approximation or contact
omission is introduced by the cache.

## Verification

```powershell
.\gradlew.bat test gpuTest
```

`test` runs the portable CPU suite. `gpuTest` creates a hidden OpenGL 4.3 context
and fails explicitly if the GPU/context is unavailable. It dispatches the actual
production shaders and compares them with the reference shaders on identical
input snapshots. Coverage includes:

- isolated bodies and approaching pairs, including bitwise output checks;
- strict contact-threshold cases and exact cached contact IDs;
- dense grids, coincident bodies, and 130-body cells;
- both cache overflow paths, including reuse of an overflowing reference grid;
- both torus seams, fast crossings, and particle insertion;
- a pair excluded at build time becoming a contact after a required rebuild;
- tiny skins forcing rebuilding, and stationary scenes actually reusing lists;
- density-driven direct-grid selection without changing contacts/corrections;
- optional adaptive indirect dispatch, inactive ping-pong parity, and final pressure.

Verification on 2026-10-24: 38 CPU tests and 8 GPU integration tests passed.

## Benchmark method

```powershell
.\scripts\run-performance-lossless.ps1
```

The hidden-context benchmark measures the full physics pipeline, including
gravity and all collision passes, using GPU timestamp queries. It does not
include rendering, trajectory preview, buffer swap, or compositor latency.
Both variants use the same fixed schedule; this is independent of the historical
adaptive-substep results in `performance.md`.

Measured on 2026-10-24. Hardware: RTX 2060 SUPER, NVIDIA 616.64,
Intel Core i5-10400, JDK 18.0.2.1.
Domain: 3440 x 1440, gravity grid 512 x 256, capacity 420,000, seed 20260730.
Each run has 120 warm-up frames and 240 measured frames. Each case is repeated
twice, reversing reference/cache order for the second pair. Projectile cases
insert 6,963 bodies at 2,000 px/s after warm-up. The timer ring holds every
sample without waiting in measured frames; readback happens only after the run.

Raw per-stage results and endpoint diagnostics are written to
`build/performance-lossless`. A non-finite body, dropped timing sample, or missing
sample fails the benchmark.

## Results

Times are milliseconds per complete physics step. Mean is the average of the
two run means (240 samples each). The P95 columns show the **larger** P95
of the two runs, not a pooled percentile.

| Scene | Reference mean | Cache mean | Speedup | Reference worst P95 | Cache worst P95 |
|---|---:|---:|---:|---:|---:|
| calm-050k | 5.357 | 3.096 | 1.73x | 5.757 | 3.901 |
| calm-100k | 10.518 | 4.822 | 2.18x | 11.035 | 6.800 |
| calm-400k | 55.197 | 21.054 | 2.62x | 56.756 | 27.548 |
| square-400k | 83.283 | 62.063 | 1.34x | 88.603 | 64.379 |
| projectile-100k | 11.046 | 8.937 | 1.24x | 11.555 | 9.617 |
| projectile-400k | 57.901 | 44.625 | 1.30x | 62.063 | 47.451 |

All 24 runs collected 240/240 samples, with no dropped slots and no non-finite
bodies. Every tested scene improved in both mean and worst-run P95. The gain
is workload-dependent: these measurements do not establish a universal
speedup, a rendering-FPS multiplier, or a 10x improvement.

## Options

`--collision-cache=false` selects the reference linked-grid implementation.
`--collision-skin=0.5` controls the broad-phase reserve in pixels; it does not
change the contact diameter or remove interactions. `--adaptive-collisions=true`
is a separate, opt-in change to the integration schedule and was not used for
the fixed-substep measurements.
