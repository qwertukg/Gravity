# Agents

How the simulation works: [DESIGN.md](DESIGN.md). Usage: [README.md](README.md).

## Baselines

All numbers: RTX 2060 SUPER, `3440×1440`, hidden window, GPU time per physics
step in ms (mean / P95), 16 collision substeps unless stated. Compare a change
against the baseline by running both revisions alternately (A/B/A/B): GPU
clocks drift up to 8% between runs.

### Collision pipeline — `collisionBenchmark`

```powershell
.\gradlew.bat collisionBenchmark --args="120 240 calm-050k,impact 2 cached"
```

Arguments: warm-up steps, measured steps, scenes, repetitions, `both|cached`
(`both` also runs the slow reference pipeline), sort interval. Output:
`build/performance-lossless`. Seed `20260730`, grid `512×256`.

| Scene | Bodies | Mean | P95 | Measured |
|---|---:|---:|---:|---|
| calm-050k | 50,000 | 2.19 | 2.40 | 2026-10-24 |
| calm-100k | 100,000 | 2.52 | 3.27 | 2026-10-26 |
| calm-400k | 400,000 | 5.20 | 6.22 | 2026-10-24 |
| square-400k | 400,000 | 8.64 | 9.58 | 2026-10-26 |
| projectile-100k | 106,963 | 4.06 | 4.16 | 2026-10-26 |
| projectile-400k | 406,963 | 8.52 | 9.39 | 2026-10-26 |
| impact | 180,026 | 4.26 | 4.55 | 2026-10-26 |
| galaxy | 200,000 | 4.28 | 4.45 | 2026-10-26 |

### Liquid — `liquidBenchmark`

```powershell
.\gradlew.bat liquidBenchmark --args="600 240 web-liquid,web-hard,pool-block 1"
```

Arguments: warm-up, measured, scenes, repetitions. Output:
`build/performance-liquid`. Walls, downward field, no mutual gravity.
`web-*` is the default start (cosmic web falling into the box) with and
without liquid; `pool-block` is a deep pool with a light block.

| Scene | Bodies | Mean | P95 | Liquid passes | Measured |
|---|---:|---:|---:|---:|---|
| web-liquid | 199,699 | 5.90 | 6.36 | 4.01 | 2026-10-25 |
| web-hard (no liquid) | 199,699 | 4.16 | 4.17 | — | 2026-10-25 |
| pool-block | 241,020 | 8.32 | 8.35 | 5.23 | 2026-10-25 |

### Breaking blocks — `blockBenchmark`

```powershell
.\gradlew.bat blockBenchmark --args="label shot,bullet 2"
```

Arguments: label (output `build/block-benchmark/<label>.txt`), scenes (`shot`,
`big`, `ram`, `sand`, `bullet`, `slow_bullet`, or `all`), repetitions. 240
steps from the shot, at most two steps queued (as the swap chain). Walls, no
mutual gravity, block mass `0.5`. Environment switches: `BLOCK_SERIES=1`
(stage times every 10 steps), `BLOCK_UNBREAKABLE=1`, `BLOCK_CLOSE=1` (shots
start at the target), `BLOCK_SUBSTEPS=N`, `BLOCK_GOVERNOR=1` (frame budget on),
`BLOCK_SLEEP=0`, `BLOCK_STEPS=N`, `BLOCK_PROBE=1` (readbacks; inflates timings
3–10×, never use for timing).

Measured 2026-10-26 (`final4.txt`), mean of two runs:

| Scene | Setup | Bodies | Mean | P95 | Grains | Pieces |
|---|---|---:|---:|---:|---:|---:|
| shot | `100 px` ball, `4000 px/s`, `600×300` block | 169,450 | 6.19 | 8.5–12.8 | ~6,900 | 13–15 |
| big | `100 px` ball, `6000 px/s`, `900×400` block | 332,937 | 9.18 | 13.2 | ~11,500 | 24–31 |
| ram | two `320 px` blocks at `1500 px/s` each | 185,083 | 6.51 | 8.0–8.5 | 4,767 | 37 |
| sand | `400×100` block at `2500 px/s` into a sand bed | 215,337 | 8.79 | 12.7–14.1 | ~6,100 | 51–56 |
| bullet | `50k` bullet, `3000 px/s` | 212,491 | 12.38 | 23.0 | ~19,500 | 115–120 |
| slow_bullet | `50k` bullet, `1500 px/s` | 212,491 | 8.15 | 16.9–19.7 | ~3,500 | 8–26 |

Breaking itself (fracture + split stages) takes `0.4–1.1 ms` of a step.

### Historical

The original baseline and each optimization step, with methods and quality
checks:

- [performance-baseline.md](docs/performance-baseline.md) — first per-stage
  baseline (calm 50k: 5.56 ms, calm 400k: 56.8 ms, square 400k: 86.5 ms).
- [performance-fft.md](docs/performance-fft.md) — shared-memory FFT.
- [performance-collisions.md](docs/performance-collisions.md) — collision grid
  experiments.
- [performance.md](docs/performance.md) — adaptive substeps (opt-in).
- [performance-neighbor-cache.md](docs/performance-neighbor-cache.md) —
  neighbour and contact cache.
- [performance-spatial-sort.md](docs/performance-spatial-sort.md) — spatial
  reordering, up to 7.4× faster physics.

## Measuring

- Fracture is chaotic: identical block runs differ by 5–10%, single bullet
  runs by much more. Average at least 2–3 runs; for one revision against
  another, alternate them.
- `BlockFractureTest` "sand shot into a block settles in its crater" fails
  about 1 in 12 runs on unchanged code. Judge it by repeated runs (≥ 60), not
  one failure.
- The `sand` bed is frictionless and never settles within 240 steps, so
  sleeping tiles do not help it.
- Target: a frame never below 60 fps, ideally 100. Startup time does not
  matter. Quality losses invisible to the eye (sub-pixel) are acceptable for
  speed; visible behaviour changes are not.
