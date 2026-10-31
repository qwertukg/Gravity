// Naval battle tags, matching NavalGpu.kt. Solid loose bodies name what they
// are in the material's .w, below the -1 of a grain broken off a block, so
// every other shader takes them for such grains:
// - a shell in flight: -(2 + shell);
// - a fire body waiting for a blast, parked along the ceiling: PARKED_TAG;
// - a hot body, born in a blast at a step: -(HOT_BASE + step). Light ones are
//   the fire of the blast, heavy ones the shell's fragments.
// Fluid bodies keep their foam (0..1) in .w instead (naval_foam.comp).
const int SHELLS = 32;
// Ints summed per shell (naval_probe.comp): bodies, then position and
// velocity in 1/SHELL_SUM_SCALE px and px/s, the fire bodies lit by its
// blast (naval_detonate.comp); two spare.
const int SHELL_SUM_INTS = 8;
const float SHELL_SUM_SCALE = 16.0;
const float PARKED_TAG = -60.0;
const float HOT_BASE = 100.0;
// Hot bodies lighter than this are fire, heavier ones fragments.
const float FIRE_MASS_LIMIT = 0.5;

int shellOf(vec4 material) {
    if (material.z == 0.0 || material.w > -2.0) return -1;
    int shell = int(-material.w) - 2;
    return shell < SHELLS ? shell : -1;
}

bool isHot(vec4 material) {
    return material.z != 0.0 && material.w <= -HOT_BASE;
}

// Simulated seconds since a hot body was born, at step [step] of [dt].
float hotAge(vec4 material, float step, float dt) {
    return (step - (-material.w - HOT_BASE)) * dt;
}

// A pseudo-random number in [0, 1) from a body and a salt.
float navalRandom(uint id, uint salt) {
    uint hash = id * 2654435761u ^ salt * 2246822519u;
    hash ^= hash >> 15;
    hash *= 2246822519u;
    hash ^= hash >> 13;
    return float(hash >> 8) / 16777216.0;
}
