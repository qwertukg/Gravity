# Shared-memory FFT

This stage replaces the global bit-reversal pass and one global
dispatch/barrier per radix-2 stage with one workgroup per transform line.

## Implementation

- A line is loaded once into a 2,048-element shared `vec2` array.
- Bit reversal is folded into that load; there is no bit-reversal dispatch.
- 256 invocations cooperatively execute every radix-2 butterfly.
- Each invocation owns at most four butterflies for the maximum supported
  2,048-point line.
- A read barrier and write barrier protect every in-place shared-memory stage.
- The completed line is written once to the global buffer.
- X and Y each use one dispatch. Y lines are accessed directly as columns;
  benchmark results did not justify adding two global transpose passes for the
  currently supported sizes.
- All configured dimensions (powers of two from 128 through 2,048) fit the
  fixed 16 KiB shared array. The same shader handles square and rectangular
  grids, so no multi-dispatch fallback is needed inside the supported range.
- The old ping-pong scratch buffer, stage program, and permutation program were
  removed.

This is a shared-memory radix-2 DIT implementation rather than Stockham.
Stockham's main advantages here—removing the separate permutation and keeping
local stages on-chip—are already achieved by the folded load, while the
in-place butterfly layout uses half as much shared memory as a ping-pong
Stockham line.

## Correctness

`Radix2FftLayoutTest` checks:

- forward and unscaled inverse transforms;
- square and rectangular layouts, including 2,048 × 128;
- absolute and L-infinity relative error against JTransforms;
- periodic rectangular convolution against direct circular convolution.

The explicit `--fft-validate=true` diagnostic reads the real GPU density
spectrum after initialization and compares it with a double-precision CPU
reference. Readback is intentionally blocking and is never used in normal or
benchmark frames.

Measured validation:

| Grid | Bodies | Maximum absolute error | Maximum relative error | RMS error |
|---|---:|---:|---:|---:|
| 128 × 128 | 1,000 | 3.60344e-5 | 3.60344e-8 | 5.05881e-6 |
| 512 × 256 | 50,000 | 3.17383e-3 | 6.34766e-8 | 4.52413e-5 |

The 2,048 × 128 and 128 × 2,048 GPU smoke runs both completed successfully.
The complete pre-existing and expanded test suite passes.

## GPU benchmark

Hardware, seed, viewport, warm-up, sample count, and scenarios are identical
to [`performance-baseline.md`](performance-baseline.md). Every case collected
240/240 samples with zero dropped queries.

The FFT total below is `forward FFT + inverse FFT X + inverse FFT Y`.

| Case | Baseline FFT, ms | Shared FFT, ms | FFT speedup | Baseline physics, ms | Shared physics, ms |
|---|---:|---:|---:|---:|---:|
| calm-050k | 0.654 | 0.205 | 3.19× | 5.559 | 5.125 |
| calm-100k | 0.745 | 0.203 | 3.67× | 10.656 | 9.996 |
| calm-400k | 0.839 | 0.206 | 4.07× | 56.800 | 56.602 |
| square-400k | 0.846 | 0.209 | 4.05× | 86.484 | 85.820 |
| projectile-100k | 0.784 | 0.209 | 3.75× | 10.995 | 10.649 |
| projectile-400k | 0.851 | 0.213 | 4.00× | 59.306 | 59.378 |

The 0.07 ms median of each transform is stable across body counts. The single
0.33 ms maximum in the projectile-400k run did not affect P95, which remained
0.071 ms.

Whole-physics improvement is intentionally small at 400k because the baseline
showed FFT at only about 1.5% of the step. The 0.07 ms projectile-400k mean
difference is below the simultaneous 0.56 ms change in collision work caused
by the nondeterministic atomic linked-list traversal; it is not attributed to
FFT. No whole-frame speedup is claimed for this stage.

## Quality comparison

Calm-050k diagnostics are bit-for-bit identical at the printed precision.
Other long-running scenes diverge slightly because collision linked-list
insertion uses atomics and is not order-deterministic. The FFT-specific GPU
comparison above avoids that confounder and bounds relative spectrum error
below `6.4e-8`. No body count, grid dimension, search radius, precision,
collision pass, or physical formula changed.
