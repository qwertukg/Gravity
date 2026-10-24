// Pressure at grain boundaries and lattice defects differs from its
// surroundings over blobs a few bodies wide; a Gaussian this wide dissolves
// them into the cluster-scale gradient. Applied as two 1D passes.
const float SMOOTHING_SIGMA_PIXELS = 8.0;
const int SMOOTHING_RADIUS = 24;

float smoothingWeight(int offset) {
    return exp(-float(offset * offset) /
        (2.0 * SMOOTHING_SIGMA_PIXELS * SMOOTHING_SIGMA_PIXELS));
}
