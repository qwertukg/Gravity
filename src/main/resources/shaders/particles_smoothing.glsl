// Pressure at grain boundaries and lattice defects differs from its
// surroundings over blobs a few bodies wide; a Gaussian this wide dissolves
// them into the cluster-scale gradient. Applied as two 1D passes.
//
// Taps are uSampleStep pixels apart (the point size): the blur grows with the
// bodies at constant cost. Sum and count are sampled alike, so the ratio does
// not alias against the lattice.
uniform int uSampleStep;
const float SMOOTHING_SIGMA_STEPS = 8.0;
const int SMOOTHING_RADIUS = 24;

float smoothingWeight(int tap) {
    return exp(-float(tap * tap) /
        (2.0 * SMOOTHING_SIGMA_STEPS * SMOOTHING_SIGMA_STEPS));
}
