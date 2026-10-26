# Gravity

A fullscreen 2D particle simulator on Kotlin/JVM and OpenGL 4.3 compute
shaders: up to 400,000 bodies with gravity, sand, liquid and breakable rigid
blocks, all computed on the GPU.

![Planetary impact: a smaller body grazes a planet, splashes a debris tail and merges](docs/impact.gif)

- **Gravity** — P3M with a GPU FFT, on a torus or inside walls; a uniform field
  from the arrow keys.
- **Sand** — hard discs with exact momentum, optional friction, granular piles.
- **Liquid** — position-based fluid with buoyancy.
- **Blocks** — rigid bodies of any outline (rectangle or picture) that chip,
  crack and split into pieces on hard impacts.

How it works: [DESIGN.md](DESIGN.md). Benchmarks and baselines:
[AGENTS.md](AGENTS.md).

## Requirements

- 64-bit Windows or Linux, JDK 18.
- GPU with OpenGL 4.3 Core, compute shaders and at least 16 storage buffers
  per compute shader (current NVIDIA, AMD and Intel drivers).
- Internet on the first Gradle run.

macOS is not supported (OpenGL 4.1 only).

## Running

```powershell
.\gradlew.bat run                                  # Windows
./gradlew run                                      # Linux
.\gradlew.bat run --args="--scenario=galaxy --point-size=2"
```

Options use `--key=value` only. By default the app starts the `cosmic-web`
scenario as liquid falling into a box under a downward field, with gravity
between bodies off.

## Controls

| Input | Action |
|---|---|
| LMB drag / release | Aim (with trajectory preview) / fire a projectile |
| RMB drag / release | Outline / create a rigid block |
| `M` | Block outline: rectangle, then each picture in `masks/` |
| `1`–`9`, `0`, `+` | Projectile diameter `10–90`, `100 px`, or `50,000` bodies |
| Top-right buttons | Restart in a test scenario |
| Mouse wheel | Time scale `1–100%` |
| `P` / `S` | Pause / stop all bodies |
| `B` | Torus ↔ walls at the screen edges |
| Arrows | Toggle a uniform field in that direction |
| `G` | Toggle gravity between bodies |
| `L` | Toggle liquid mode |
| `C` / `R` | Clear all bodies / restart the scenario |
| `Esc` | Exit |

## Options

The main ones; the full list with limits is
[`GravityConfig`](src/main/kotlin/dev/gravity/GravityConfig.kt).

| Option | Default | Meaning |
|---|---:|---|
| `--scenario=NAME` | `cosmic-web` | Start scenario, or `none` for a uniform random start |
| `--walls`, `--liquid` | `true` | Start with walls / in liquid mode |
| `--mutual-gravity` | `false` | Start with gravity between bodies |
| `--field=DIRS` | `down` | `off` or a list of `up,down,left,right` |
| `--point-size=S` | `1` | Body size in px; scales diameter, counts and other size defaults |
| `--bodies=N` / `--capacity=N` | `50000/S²` / `400000` | Initial bodies / GPU buffer capacity |
| `--grid=WxH` | `512x256` | FFT grid, powers of two (walls: at most `1024` per axis) |
| `--collisions=N` | `16` | Collision substeps |
| `--friction=F` | `0` | Coulomb friction of every body |
| `--block-density=F` | `0.5` | Mass per block body (others have `1`) |
| `--block-strength=F` | `400` | Critical impact speed, px/s; `Infinity` = unbreakable |
| `--gpu-budget-ms=F` | `8.5` | Above this GPU frame time fewer substeps run |
| `--sleep=true\|false` | `true` | Sleeping tiles for resting bodies |
| `--seed=N` | random | Reproducible start |
| `--benchmark=calm\|projectile` | — | Finite benchmark with the GPU profiler |

Scenarios: `turbulent-cloud`, `galaxy`, `galaxy-collision`, `spinning-planet`,
`planetary-impact`, `ringed-planet`, `binary-stars`, `galaxy-flyby`,
`planetary-system`, `cosmic-web`; block scenes (`bullets`, `cannonball`,
`crush`, `stack`, `big-on-small`, `ram`, `pile`, `liquid`, `bounce`, `wall`,
`masses`, `sliding`, `bodies`, `slow-body`, `thrown`, `drops`); and the
test-scenario buttons (`wall-bullet`, `liquid-wall-bullet`, `bullet`,
`slow-bullet`, `big-block-bullet`, `small-block-bullet`, `unbreakable-bullet`,
`shot`, `big-shot`, `ramming`, `sand-drop`, `crater`). Distances and counts
are authored for a `1440 px` tall screen and scale with it.

## Tests

```powershell
.\gradlew.bat test      # CPU tests
.\gradlew.bat gpuTest   # GPU tests in a hidden window (OpenGL 4.3)
```

`BlockScenarioRunTest` writes a 3 s video of each scenario to
`build/test-videos`; `SCENARIO_FILTER=<part of a title>` runs only matching
ones.

Distribution: `.\gradlew.bat installDist`, then
`.\build\install\gravity\bin\gravity.bat`.
