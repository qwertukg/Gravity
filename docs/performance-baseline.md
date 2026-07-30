# GPU performance baseline

This document records the unoptimized pipeline before the shared-memory FFT
and compact collision-grid work. The measurements were captured on
2026-07-30 from commit `80bca79` plus profiling-only instrumentation.

## Measurement method

- GPU: NVIDIA GeForce RTX 2060 SUPER, driver 591.86.
- CPU: Intel Core i5-10400.
- OpenGL: 4.3.0 NVIDIA 591.86.
- JVM: Java 18.0.2.1, `-Xms512m -Xmx2g -XX:+UseZGC`.
- Viewport: 3440 × 1440.
- FFT grid: 512 × 256.
- Collision substeps: 16, plus the existing final zero-drift contact pass.
- Seed: `20260730`.
- Each case uses 120 warm-up frames followed by 240 measured frames.
- V-sync is disabled.

The profiler inserts `GL_TIMESTAMP` queries at stage boundaries. Timestamp
pairs can be nested, allowing `frame`, `physics step`, and
`collision substeps total` to be measured together with their component
stages. Sixteen complete query sets form a ring. A slot is reused only after
the final query reports `GL_QUERY_RESULT_AVAILABLE`; the CPU never waits for a
query result. All runs collected 240 of 240 requested frames with zero dropped
samples.

`frame` measures GPU work submitted from the start of application-frame
processing through the final draw. Buffer swap and compositor latency are
outside this interval. The four individual collision rows include all 17
contact passes and are sums of repeated invocations. `collision substeps total`
is the continuous interval around the 16 configured drift substeps and excludes
the existing final zero-drift pass. `gravity grid` includes clear, particle
insertion, and density-to-complex initialization. Projectile cases keep the
trajectory visible so `trajectory` and trajectory rendering have direct GPU
samples.

Reproduce the complete matrix with:

```powershell
.\scripts\run-performance-baseline.ps1
```

The script builds the distribution and writes one raw log per case under
`build/performance-baseline`.

## Scenario matrix

| Case | Initial bodies | Measured bodies | Initial state | Projectile / preview |
|---|---:|---:|---|---|
| calm-050k | 50,000 | 50,000 | uniform, zero velocity | none |
| calm-100k | 100,000 | 100,000 | uniform, zero velocity | none |
| calm-400k | 400,000 | 400,000 | uniform, zero velocity | none |
| square-400k | 400,000 | 400,000 | centered 632 px dense square | none |
| projectile-100k | 100,000 | 106,963 | uniform, 120-frame warm-up | 6,963-body, 100 px projectile at 2,000 px/s |
| projectile-400k | 400,000 | 406,963 | uniform, 120-frame warm-up | 6,963-body, 100 px projectile at 2,000 px/s |

The projectile is launched from `(0.25 × width, 0.5 × height)` along +X. The
same origin and velocity are used for the measured trajectory. Capacity is
420,000 in every case, so allocation size is constant and the 400k projectile
case has enough inactive slots.

## Mean GPU time

All values are milliseconds. A dash means that the stage is intentionally not
active in that scenario.

| Stage | calm 50k | calm 100k | calm 400k | square 400k | projectile 100k | projectile 400k |
|---|---:|---:|---:|---:|---:|---:|
| collision clear | 0.665 | 1.411 | 5.588 | 0.631 | 1.341 | 5.457 |
| collision build | 0.888 | 1.754 | 6.466 | 1.102 | 1.667 | 6.386 |
| collision count | 1.564 | 3.023 | 18.377 | 34.067 | 3.208 | 19.873 |
| collision solve | 1.450 | 2.984 | 18.874 | 48.217 | 3.231 | 20.570 |
| collision substeps total | 4.336 | 8.674 | 46.470 | 79.166 | 8.932 | 49.291 |
| gravity grid | 0.027 | 0.039 | 0.109 | 0.120 | 0.042 | 0.117 |
| forward FFT | 0.086 | 0.086 | 0.086 | 0.087 | 0.088 | 0.091 |
| convolution | 0.015 | 0.015 | 0.015 | 0.015 | 0.015 | 0.015 |
| inverse FFT X | 0.340 | 0.359 | 0.378 | 0.374 | 0.378 | 0.382 |
| inverse FFT Y | 0.228 | 0.300 | 0.375 | 0.385 | 0.318 | 0.378 |
| gravity kick + near correction | 0.255 | 0.645 | 6.492 | 1.446 | 0.666 | 5.996 |
| trajectory | — | — | — | — | 3.281 | 3.760 |
| render | 0.110 | 0.170 | 0.537 | 0.224 | 0.181 | 0.620 |
| physics step | 5.559 | 10.656 | 56.800 | 86.484 | 10.995 | 59.306 |
| frame | 5.670 | 10.827 | 57.339 | 86.710 | 14.460 | 63.688 |

## P95 GPU time

| Stage | calm 50k | calm 100k | calm 400k | square 400k | projectile 100k | projectile 400k |
|---|---:|---:|---:|---:|---:|---:|
| collision clear | 0.674 | 1.421 | 5.677 | 0.656 | 1.421 | 5.801 |
| collision build | 0.898 | 1.764 | 6.549 | 1.300 | 1.748 | 6.753 |
| collision count | 1.577 | 3.044 | 19.277 | 34.532 | 3.281 | 22.502 |
| collision solve | 1.463 | 3.005 | 19.606 | 49.900 | 3.314 | 23.653 |
| collision substeps total | 4.364 | 8.720 | 47.796 | 81.060 | 9.056 | 54.053 |
| gravity grid | 0.028 | 0.040 | 0.110 | 0.121 | 0.043 | 0.115 |
| forward FFT | 0.086 | 0.086 | 0.087 | 0.088 | 0.088 | 0.088 |
| convolution | 0.016 | 0.016 | 0.016 | 0.016 | 0.016 | 0.016 |
| inverse FFT X | 0.396 | 0.370 | 0.430 | 0.403 | 0.420 | 0.408 |
| inverse FFT Y | 0.260 | 0.353 | 0.401 | 0.448 | 0.397 | 0.407 |
| gravity kick + near correction | 0.272 | 0.648 | 6.648 | 1.509 | 0.673 | 6.593 |
| trajectory | — | — | — | — | 3.683 | 4.145 |
| render | 0.111 | 0.171 | 0.538 | 0.246 | 0.178 | 0.854 |
| physics step | 5.639 | 10.759 | 58.191 | 88.570 | 11.204 | 63.719 |
| frame | 5.751 | 10.932 | 58.769 | 88.785 | 14.745 | 67.836 |

## Quality snapshot

The baseline stage changes only instrumentation and deterministic setup; no
shader, dispatch, barrier, integration, collision formula, grid size, search
radius, body count, or precision was changed. The full pre-existing test suite
passes. The following end-of-run diagnostics provide fixed-seed comparison
points for later stages:

| Case | Covariance area, px² | Equivalent diameter, px | Core 90%, px² | Core 99%, px² | Max speed, px/s |
|---|---:|---:|---:|---:|---:|
| calm-050k | 5,110,750 | 2,550.9 | 8,947,290 | 10,017,212 | 238.2 |
| calm-100k | 5,144,434 | 2,559.3 | 8,986,848 | 9,959,764 | 116.3 |
| calm-400k | 5,179,402 | 2,568.0 | 8,977,082 | 9,977,102 | 49.7 |
| square-400k | 412,843 | 725.0 | 416,715 | 421,894 | 288.9 |
| projectile-100k | 4,350,715 | 2,353.6 | 7,985,974 | 9,519,305 | 1,164.8 |
| projectile-400k | 4,686,170 | 2,442.7 | 8,711,775 | 10,002,855 | 623.2 |

## Bottleneck assessment

- The configured collision substeps alone consume 78.0% of the 50k calm
  physics step, 81.4% at 100k, 81.8% at 400k, and 91.5% in the dense square.
- Including the final zero-drift collision pass makes collision work an even
  larger share. `collision count` and `collision solve` dominate dense scenes,
  directly supporting the planned compact grid and cell-centric solver work.
- Forward FFT plus both inverse transforms costs 0.65–0.84 ms. Even eliminating
  it completely would improve the measured 400k calm physics step by only about
  1.5%. FFT dispatch reduction should still be tested as its own stage, but it
  cannot provide the required whole-frame 2× target on this baseline.
- At 400k uniform density, gravity kick plus near correction is the largest
  non-collision stage at 6.49 ms.

No speedup is claimed in this document; these numbers are the comparison point
for subsequent optimization commits.
