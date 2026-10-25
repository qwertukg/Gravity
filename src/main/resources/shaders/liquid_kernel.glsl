// Position-based fluid (Macklin & Mueller 2013) in screen pixels, matching
// LiquidKernel.kt: the 2D poly6 kernel for density, the 2D spiky kernel for
// gradients. Neighbours come from a linked-list grid with cells no smaller
// than the kernel radius, so a 3 x 3 stencil finds them all.
//
// Every pass searches around the cell a body had when the grid was built,
// not its current one: then i finds j exactly when j finds i, so every pair
// term is applied on both sides and momentum is kept even when bodies cross
// cells during the iterations.
layout(std430, binding = 6) buffer Cells { int cells[]; };
uniform vec2 uViewport;
uniform int uBounded;
uniform int uGridWidth;
uniform int uGridHeight;
uniform float uKernelRadius;
uniform float uPoly6;          // 4 / (pi h^8)
uniform float uSpikyGradient;  // -30 / (pi h^5)
uniform float uRestDensity;

float poly6(float distanceSquared) {
    float x = uKernelRadius * uKernelRadius - distanceSquared;
    return x > 0.0 ? uPoly6 * x * x * x : 0.0;
}

// Gradient with respect to the first body of delta = first - second.
vec2 spikyGradient(vec2 delta, float distance) {
    if (distance >= uKernelRadius || distance <= 0.0) return vec2(0.0);
    float x = uKernelRadius - distance;
    return delta * (uSpikyGradient * x * x / distance);
}

ivec2 liquidCell(vec2 position) {
    return clamp(ivec2(floor(position * vec2(uGridWidth, uGridHeight))),
        ivec2(0), ivec2(uGridWidth - 1, uGridHeight - 1));
}

int liquidCellIndex(ivec2 cell) {
    return cell.x + cell.y * uGridWidth;
}

ivec2 homeCell(uint id) {
    int cell = cells[id];
    return ivec2(cell % uGridWidth, cell / uGridWidth);
}

// False for a cell beyond a wall; wraps on the torus.
bool neighbourCell(inout ivec2 cell) {
    if (uBounded != 0) {
        return all(greaterThanEqual(cell, ivec2(0))) &&
            all(lessThan(cell, ivec2(uGridWidth, uGridHeight)));
    }
    cell = (cell + ivec2(uGridWidth, uGridHeight)) % ivec2(uGridWidth, uGridHeight);
    return true;
}

vec2 pixelDelta(vec2 first, vec2 second) {
    vec2 delta = (first - second) * uViewport;
    if (uBounded == 0) delta -= floor(delta / uViewport + vec2(0.5)) * uViewport;
    return delta;
}
