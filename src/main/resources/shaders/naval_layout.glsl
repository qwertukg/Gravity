// Naval battle shells, matching NavalGpu.kt. A shell is a ball of solid loose
// bodies whose material names it in .w as -(2 + shell): below the -1 of a
// grain broken off a block, so every other shader takes it for such a grain.
const int SHELLS = 32;
// Ints summed per shell (naval_probe.comp): bodies, then position and
// velocity in 1/SHELL_SUM_SCALE px and px/s; three spare.
const int SHELL_SUM_INTS = 8;
const float SHELL_SUM_SCALE = 16.0;

int shellOf(vec4 material) {
    if (material.z == 0.0 || material.w > -2.0) return -1;
    int shell = int(-material.w) - 2;
    return shell < SHELLS ? shell : -1;
}
