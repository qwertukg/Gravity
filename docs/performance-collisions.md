# Collision grid and solver experiments

The baseline identified collision traversal as 78–92% of the physics step.
Several replacements for the original sparse linked grid were implemented and
measured on the same RTX 2060 SUPER, 3440 × 1440 viewport, 512 × 256 gravity
mesh, and seed 20260730.

## Compact-grid experiments

The requested CSR pipeline was tested in three forms:

1. A full-grid count, exclusive scan, and scatter over all collision cells.
   The screen-sized collision grid has roughly 3.9 million cells, so scanning
   it cost about 1.35 ms per collision pass even with only 1,000 bodies.
2. A sparse occupied-cell CSR pipeline. It compacted occupied cell IDs, scanned
   their counts, and scattered particle IDs into contiguous ranges. Physics
   time regressed from 5.13 to 14.48 ms at 50k and from 56.60 to 89.35 ms at
   400k.
3. A cell-centric solver with one 256-thread workgroup per occupied cell. The
   uniform scenes have mostly one-particle cells, leaving almost every lane
   idle; it was slower than the sparse CSR body-centric version.

A hybrid dense-cell classifier was also measured. Its additional count
atomics alone raised calm-50k physics time to 7.91 ms.

These implementations were reverted as required by the project rule to reject
optimizations that do not win. The reason is structural: at this viewport the
linked representation visits only occupied cells and performs one atomic
insertion per body, while CSR adds global ordering work on every one of the 17
collision passes. A radix sort has the same lower bound plus multiple histogram
and scatter passes. Keeping a slower CSR implementation would make all required
50k–400k scenes regress.

## Retained solver changes

Two changes measured faster without altering a contact:

- The old silent `MAX_PARTICLES_PER_CELL = 64` traversal limit was removed.
  Linked lists are now traversed to their actual end, including dense cells.
- `collisionCount` considers a geometric pair once (`otherId > id`) and
  atomically increments both integer contact counts. The counts are cleared in
  the already-required sparse clear pass. This halves pair geometry work and
  preserves the exact `max(ownContacts, otherContacts)` pair weight used by the
  solver. It uses integer atomics only; there is no float CAS.

Short paired measurements at 400k reduced calm physics from 56.427 to
54.870 ms (2.8%). The full 240-frame dense square measurement is 83.163 ms,
versus 85.820 ms after the FFT stage (3.1%). At 50k the difference is below
run-to-run noise, so no speedup is claimed there.

## Correctness

`CpuCollisionReferenceTest` is an independent O(N²) periodic detector. It
compares the grid candidate set exactly and covers:

- 130 bodies in one collision cell (8,385 pairs);
- multiple bodies at identical coordinates;
- deterministic finite and antisymmetric fallback normals;
- contacts through both torus seams;
- empty and singleton cells.

The previous 64-body truncation fails the first case; the retained traversal
has no cap. Existing toroidal grid, deterministic seed, square placement, FFT,
and projectile tests continue to pass.

## Conclusion

Compact CSR and shared-memory cell-centric traversal were tried, benchmarked,
and rejected on the target workload. The retained sparse linked grid is not an
unexamined fallback: it is the fastest measured representation for the current
screen-sized, low-occupancy grid. Dense correctness is restored by removing the
cap, while the symmetric pair-count pass gives a modest GPU-time reduction.
