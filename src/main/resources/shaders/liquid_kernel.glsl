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

// Coincident bodies have no direction between them, so the liquid could
// never push them apart: a clump merged in a wall corner would fly out as one
// dense, red dot for good. They get the collision solver's fallback instead,
// a fixed pseudo-random direction per pair, opposite for the two bodies.
const float COINCIDENT_DISTANCE = 1e-4;

vec2 fallbackDirection(uint first, uint second) {
    uint low = min(first, second);
    uint high = max(first, second);
    uint hash = low * 1664525u + high * 1013904223u;
    float angle = float(hash & 65535u) * (6.28318530718 / 65536.0);
    vec2 direction = vec2(cos(angle), sin(angle));
    return first < second ? direction : -direction;
}

// Gradient with respect to the first body of delta = first - second.
vec2 spikyGradient(vec2 delta, float distance, uint first, uint second) {
    if (distance >= uKernelRadius) return vec2(0.0);
    vec2 direction = distance > COINCIDENT_DISTANCE ? delta / distance : fallbackDirection(first, second);
    float x = uKernelRadius - distance;
    return direction * (uSpikyGradient * x * x);
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

// Walls: a body near a wall misses the neighbours beyond it, reads too low a
// density and the liquid packs against the wall. Each wall is therefore
// filled with virtual rows continuing the hexagonal packing, so a body at
// rest against it reads the rest density. A row at distance delta with line
// density 1/d adds (1/d) * poly6 * 32/35 * a^7, a^2 = h^2 - delta^2, exactly.
uniform float uBodyDiameter;
uniform float uRowStep;

// Density of the virtual rows beyond a wall [distance] px from the body
// (x) and its derivative with respect to that distance (y).
vec2 wallDensity(float distance) {
    vec2 result = vec2(0.0);
    float h2 = uKernelRadius * uKernelRadius;
    // The first virtual row continues the packing below the row that rests
    // against the wall, whose centres are half a diameter from it.
    float delta = distance - 0.5 * uBodyDiameter + uRowStep;
    for (int row = 0; row < 8 && delta < uKernelRadius; row++) {
        float a2 = h2 - max(delta, 0.0) * max(delta, 0.0);
        float a = sqrt(a2);
        float a5 = a2 * a2 * a;
        result += vec2(a5 * a2, -7.0 * max(delta, 0.0) * a5);
        delta += uRowStep;
    }
    return result * (uPoly6 * 32.0 / 35.0 / uBodyDiameter);
}

// Density from all walls (x) and its gradient with respect to the body
// position (yz), for a body at [positionPixels].
vec3 wallsDensity(vec2 positionPixels) {
    vec3 result = vec3(0.0);
    if (uBounded == 0) return result;
    vec2 low = wallDensity(positionPixels.x);
    vec2 high = wallDensity(uViewport.x - positionPixels.x);
    vec2 bottom = wallDensity(positionPixels.y);
    vec2 top = wallDensity(uViewport.y - positionPixels.y);
    result.x = low.x + high.x + bottom.x + top.x;
    result.yz = vec2(low.y - high.y, bottom.y - top.y);
    return result;
}

vec2 pixelDelta(vec2 first, vec2 second) {
    vec2 delta = (first - second) * uViewport;
    if (uBounded == 0) delta -= floor(delta / uViewport + vec2(0.5)) * uViewport;
    return delta;
}
