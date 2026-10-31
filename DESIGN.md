# Design

How the simulation works. Usage is in [README.md](README.md); performance
baselines and how to measure them are in [AGENTS.md](AGENTS.md).

Everything runs in OpenGL 4.3 compute shaders. Body state stays in VRAM:
positions and velocities as `vec4(x, y, vx, vy)`, materials as
`vec4(mass, friction, solid, unused)`. The CPU reads nothing back each frame,
except a small copy of breakable blocks (see [Pieces](#pieces)).

## Physics step

One step of constant `dt` (`1/120 s`), kick-drift-kick:

1. **Gravity** (optional, key `G`). P3M: CIC deposit onto a grid, shared-memory
   radix-2 FFT, periodic (torus) or zero-padded (walls) Green's function with
   Gaussian Ewald splitting, CIC interpolation back, and an exact short-range
   correction through cell linked lists. Gravity is normalized to the total
   mass, so adding bodies redistributes mass instead of adding it. A uniform
   field (arrow keys) is added to every body.
2. **Collision substeps** (`16`). Each substep drifts bodies, finds contacts
   and solves them with a Jacobi pass. With blocks present, every pass is
   followed by the rigid projection of blocks.
3. **Liquid** (liquid mode only): one position-based fluid solve.
4. **Fracture** of blocks and, on the CPU, splitting them into pieces.

Supporting mechanisms:

- **Neighbour cache.** The broad phase (spatial hash, `3 × 3` cells) is reused
  while every body stays within half the skin; actual contacts are tested on
  every substep. Fast scenes rebuild the ordinary grid.
- **Spatial sort.** Every second step bodies are reordered along a Morton
  curve of small tiles, so neighbours have nearby IDs. A pure permutation;
  block member lists follow it.
- **Frame budget.** While a frame takes more than `--gpu-budget-ms` (`8.5`),
  the next steps run proportionally fewer substeps, down to half; they come
  back one per frame when there is room.
- **Sleeping tiles** (`32 px`). A body that is held (touches a body, a wall or
  is a block member), moved less than `--sleep-speed × dt` and was pushed less
  than `0.005` diameters keeps its tile quiet. A tile is awake while it or a
  neighbour holds a moving or unsupported body; a block is awake while any of
  its members is in an awake tile. Sleeping bodies get no kick, drift or
  contacts, their speed is zeroed, and to an awake body they are immovable,
  like a wall. Everything stays awake for two steps after any scene change;
  liquid never sleeps.

Walls (key `B`) are immovable bodies: a body touching one takes the full
correction a pair would split, so hitting a wall equals hitting a mirror
image.

## Sand

Loose bodies are equal hard discs of contact diameter `1.128 × point size`
(the disc with the area of the point), unit mass, restitution `0`.

- **Contact solve.** Jacobi: each body of a pair removes the other's share of
  penetration and approaching normal speed, weighted `m_j / (m_i + m_j)`, so
  impulses are equal and opposite and momentum is exact. Position correction
  becomes velocity as `Δv = Δx / Δt`.
- **Friction.** Optional Coulomb (`--friction`, default `0`): the tangential
  change is at most `μ` times the normal change and never reverses sliding;
  pair coefficient `sqrt(μ_i μ_j)`. At `μ = 0` the code is skipped entirely.
- **Granular behaviour.** Frictionless equal discs lock into a hexagonal
  packing, so a pile behaves as a granular solid: a block finds its level
  where it lands, a light one floats on top, a heavy one sinks until the pile
  below carries it.
- **Pressure.** Written by the final pass and shown as colour from cyan to red
  (`--pressure-max`).

Crowds (hundreds of bodies pressed into one pixel by an impact) need special
handling, otherwise they cost the square of their size and never come apart:

- **Crowd relief.** A pair where either body has more than `24` contacts and
  whose centres are closer than half a diameter moves apart as if each had six
  contacts, by position only, at most half a diameter per pass. A crowd of up
  to `48` at rest is left alone, so sand in a crater stays still.
- **Sampling.** A body with more than `12` loose contacts solves `8` of them on
  average, chosen by a hash of the pair, so both bodies of a pair pick it or
  neither and symmetry holds. The sample's sum estimates the full sum.
- **Cell cap.** With blocks present, a grid cell lists at most `12` loose
  bodies; the rest are ghosts in a separate grid that only block members walk.
  Ghosts collide with blocks and walls, not with loose bodies, so pairs stay
  symmetric.
- **Against a block.** A loose body's impulse against a block takes its whole
  approach speed, not its contact count's share of it: the block's push
  moves positions only, so nothing else would stop it. Its pairs with the
  block's members are one contact, found once and shared among them.

## Liquid

Key `L` turns the bodies outside blocks into a position-based fluid (Macklin &
Müller 2013), solved once per step after the collision substeps:

1. A linked-list grid with cells of at least the kernel radius `h`
   (`--liquid-kernel`, `2` diameters) is built once per step.
2. `--liquid-iterations` (`12`) Jacobi iterations solve the density constraint
   `C = max(ρ/ρ0 − 1, 0)` with the poly6 kernel for density and spiky for its
   gradient. `ρ0` is the density of the hexagonal packing, so liquid keeps the
   spacing of sand. The constraint only pushes, so a free surface never clumps.
   Corrections become velocity as `Δv = Δx / Δt`.
3. XSPH viscosity (`--liquid-viscosity`, `0.1`).

- Liquid bodies skip hard contacts with each other, so they never lock into a
  packing and flow under any pressure.
- **Walls** are virtual rows continuing the hexagonal packing (closed-form
  poly6 density), so liquid reads rest density at a wall instead of packing
  onto it. A correction that would cross a wall stops at the radius and loses
  that velocity, otherwise a deep pool boils at its floor.
- **Blocks.** Members count in the density around them and take the liquid's
  reaction scaled by the mass ratio; the rigid projection spreads it over the
  block. Buoyancy follows: `--block-density` below `1` floats, above `1`
  sinks. A hard contact only puts a liquid body that got inside a block back
  on its outline, by position only.
- Each pass searches around the cell a body had at grid build time, so pairs
  are seen from both sides and momentum is exact.
- Fewer than `12` iterations leave a Jacobi residual in deep or
  self-gravitating liquid, which then stirs itself up.
- Pressure colour shows the compression `C`.

## Blocks

Right-drag fills a rectangle (or the black pixels of a picture from `masks/`,
key `M`) with hexagonally packed bodies at rest and glues them into one rigid
body. Up to `256` blocks exist at once.

- **Members.** Every member is an ordinary body of mass `--block-density`
  (`0.5`): it feels gravity and the field and hits walls on its own. Members of
  one block never collide with each other; cells tagged with a single block
  are skipped.
- **Rigid projection.** After every collision pass the block is projected onto
  the rigid motion that best fits its members (2D shape matching: centre of
  mass, best-fit rotation, velocity and angular velocity keeping momentum and
  angular momentum). Member lists are padded to 256-body workgroups, so each
  workgroup sums one block in shared memory: no float atomics, deterministic.
- **Shape.** Each block has a signed distance field on a grid in its own frame
  (`GlueShape`): outside, the distance to the nearest member minus a radius;
  inside, members are closed into a solid and the field points to the nearest
  face. Smoothing makes faces flat while corners stay at their member. Holes
  and concave outlines collide as drawn. A convex hull keeps blocks out of
  walls.
- **Contacts.** A contact with a member is a contact with the block: normal and
  depth come from the field, and the correction is split by the block's rigid
  response (mass, inertia, lever arm). Mass splitting shares a block among its
  simultaneous partners (the floor, each block, and the loose bodies touching
  it as one), so stacks are never overshot.
- **Split impulse.** Pushes out of blocks and walls move positions only; speed
  changes by impulses alone, so a body is not thrown back as fast as it came.
- **No tunnelling.** A body that crossed the outline during a substep is
  sphere-traced back along its path; a centre still inside is put back on the
  outline. A body wedged between two blocks, or a block and a wall, moves the
  blocks instead. A block pressed against a wall loses its speed into it.
- **Restitution** `--block-restitution` (`0.2`) between blocks and walls; each
  pair remembers its approach speed during the impact and aims at that times
  restitution. Below `20` diameters/s nothing bounces, so rest stays rest. A
  pair that has pushed for two steps without reaching its target is held,
  under load or wedged: its impact is over and it merely touches.
- **Friction** `--block-friction` (`0.5`) against walls and blocks, Coulomb
  along the rigid response.
- **Mass.** A block's mass grows with its area; falling and floating depend on
  density only.

## Breaking blocks

Blocks are brittle. An impact breaks material when the approach speed exceeds
the critical speed `--block-strength` (`400 px/s`), the speed at which impact
stress (≈ density × sound speed × speed) reaches the material strength. Rest,
slow contacts and the weight of a pile never break anything.
`--block-strength=Infinity` makes blocks unbreakable.

- **Chips.** A member hit faster than its own strength breaks off as a grain, if
  the contact brings the energy. Member strengths follow a Weibull
  distribution (modulus `6`) around the critical speed, so a hit just above it
  chips weak spots and a harder one the whole face.
- **Cracks.** The excess energy of each contact, `μ(v² − vc²)` with `μ` its
  share of the effective mass, pays for a crack at `20 × mass × vc²` per
  member. Each pass the crack starts at the hardest-hit member and runs into
  the block one member per step, wandering and occasionally branching, until
  the energy is spent or it exits. Each step examines the `7 × 7` cells around
  the tip in parallel, one lane per cell.
- **Grains.** Broken members become loose bodies that keep the block's mass and
  friction (they sink in liquid like the block). A chip keeps the block's
  local motion and flies back along the impact at up to half its excess speed,
  capped at the critical speed; the block recoils, so momentum and angular
  momentum are kept.
- **Carving.** The distance field is updated in the same pass within two
  diameters of each broken member, one invocation per node and member. A free
  member leaves a crater whose floor and walls are ordinary faces, so sand
  rests in it. A member broken among neighbours (a crack) leaves a gap
  narrower than a body whose walls stay deep, so bodies are not pressed into
  it. Centre of mass, mass and inertia are updated from what broke off alone.

### Pieces

A one-member-wide crack cuts a block: members more than `1.2` diameters apart
no longer hold together.

- The GPU flags a block when a broken member leaves its neighbours in more than
  one arc. After every step the blocks, flags and surviving members are copied
  to CPU-visible memory behind a fence; the CPU waits for the copy one step
  later, with the next step already queued, so the GPU never idles.
- The CPU checks a flagged block at once and any block that lost members within
  `8` steps. It keeps each block's lattice and floods from the neighbours of
  the lost members; a search that runs out before meeting the others has found
  a piece, so only detached pieces are walked.
- The largest piece stays the block. Every other piece of at least `16` bodies
  becomes a new block with the same rigid motion, its own shape and hull;
  smaller pieces, and pieces that find no free block slot, crumble into grains.
- Search and shapes run on a worker thread (shapes on all cores). What is ready
  within `2 ms` is glued in the same step, the rest in the next, so a block
  shattering into hundreds of pieces never stalls a frame.
- Since the copy is a step old, a piece names its parent's slots and the GPU
  assigns the current bodies and motion when it is glued; a member broken in
  between leaves the piece at once.

## Naval battle

A game on the same physics, with nothing animated (`NavalBattle`):

- **Ships** are breakable blocks in the outline of their class (`ShipClass`,
  polygons of a side view) floating on a liquid pool. An engine adds the same
  velocity to every member; the rigid projection moves the block by it.
- **Shells** are balls of solid loose bodies of mass `3` whose material names
  the shell in `.w` (`-2 - shell`; to every other shader a loose grain), so
  they follow spatial sorting. Every gun of a ship fires one per salvo, from a
  pivot in the ship's rest frame; a gun aft never fires lower than its line
  over the ship.
- **Probe.** Each frame one pass sums every shell's bodies, and the ships'
  block states are copied with the sums behind a fence; the CPU reads it a
  frame later, with the next step queued.
- **Detonation.** A shell whose velocity is off its free flight (launch
  velocity plus the field) struck something, at once or by the liquid's drag.
  Its bodies then get a radial velocity from their centre, the rim fastest,
  and become ordinary grains: the blast is their impacts, which chip and crack
  blocks and splash liquid.
- A ship is destroyed when its block holds less than `5%` of its bodies.
