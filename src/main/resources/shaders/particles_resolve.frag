#version 430 core

layout(location = 0) out vec4 color;
uniform sampler2D uAccumulation;
uniform sampler2D uHorizontal;

// Packed bodies sit ~1.13 px apart, so some pixels inside a cluster receive
// no 1 px point and form dark lines where the lattice beats against the pixel
// grid. Such a hole has most of its 8 neighbours occupied; pixels just outside
// a cluster edge or next to an isolated body have far fewer.
const int INTERIOR_NEIGHBOURS = 5;
// Neighbour distance for the hole test: about half the point size.
uniform int uGapPixels;

#include "particles_smoothing.glsl"

// Cyan -> green -> yellow -> orange -> red. The plain HSV ramp has RGB kinks
// at pure green and yellow that read as contour lines; this is the same ramp
// with those corners slightly rounded, resampled at equal OKLab distances.
const int RAMP_KNOTS = 33;
const vec3 PRESSURE_RAMP[RAMP_KNOTS] = vec3[](
    vec3(0.00000, 1.00000, 0.94017),
    vec3(0.00000, 1.00000, 0.86882),
    vec3(0.00000, 1.00000, 0.79615),
    vec3(0.00000, 1.00000, 0.72138),
    vec3(0.00000, 1.00000, 0.64333),
    vec3(0.00000, 1.00000, 0.56002),
    vec3(0.00003, 1.00000, 0.46782),
    vec3(0.00042, 1.00000, 0.35845),
    vec3(0.00687, 1.00000, 0.20134),
    vec3(0.28435, 1.00000, 0.00172),
    vec3(0.45611, 1.00000, 0.00004),
    vec3(0.57775, 0.99990, 0.00000),
    vec3(0.67928, 0.99912, 0.00000),
    vec3(0.76972, 0.99568, 0.00000),
    vec3(0.85100, 0.98471, 0.00000),
    vec3(0.91495, 0.96003, 0.00000),
    vec3(0.95352, 0.92462, 0.00000),
    vec3(0.97441, 0.88551, 0.00000),
    vec3(0.98595, 0.84537, 0.00000),
    vec3(0.99244, 0.80483, 0.00000),
    vec3(0.99607, 0.76400, 0.00000),
    vec3(0.99805, 0.72281, 0.00000),
    vec3(0.99909, 0.68116, 0.00000),
    vec3(0.99961, 0.63888, 0.00000),
    vec3(0.99985, 0.59573, 0.00000),
    vec3(0.99995, 0.55141, 0.00000),
    vec3(0.99999, 0.50548, 0.00000),
    vec3(1.00000, 0.45731, 0.00000),
    vec3(1.00000, 0.40590, 0.00000),
    vec3(1.00000, 0.34957, 0.00000),
    vec3(1.00000, 0.28498, 0.00000),
    vec3(1.00000, 0.20376, 0.00000),
    vec3(1.00000, 0.05983, 0.00000)
);

vec3 rampKnot(int index) {
    // Linear extrapolation keeps the end segments at the same speed.
    if (index < 0) return 2.0 * PRESSURE_RAMP[0] - PRESSURE_RAMP[1];
    if (index >= RAMP_KNOTS) {
        return 2.0 * PRESSURE_RAMP[RAMP_KNOTS - 1] - PRESSURE_RAMP[RAMP_KNOTS - 2];
    }
    return PRESSURE_RAMP[index];
}

vec3 pressureColor(float pressure) {
    // Catmull-Rom keeps the ramp's slope continuous between knots.
    float position = clamp(pressure, 0.0, 1.0) * float(RAMP_KNOTS - 1);
    int index = min(int(position), RAMP_KNOTS - 2);
    float t = position - float(index);
    vec3 p0 = rampKnot(index - 1);
    vec3 p1 = rampKnot(index);
    vec3 p2 = rampKnot(index + 1);
    vec3 p3 = rampKnot(index + 2);
    return clamp(0.5 * (
        2.0 * p1 +
        (p2 - p0) * t +
        (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t * t +
        (3.0 * p1 - p0 - 3.0 * p2 + p3) * t * t * t
    ), 0.0, 1.0);
}

vec2 accumulated(ivec2 pixel, ivec2 size) {
    return texelFetch(uAccumulation, clamp(pixel, ivec2(0), size - 1), 0).rg;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(uAccumulation, 0);
    // Bodies add the fraction of each pixel they cover; it sets brightness,
    // so disc edges and small bodies are antialiased.
    float coverage = min(accumulated(pixel, size).y, 1.0);
    if (coverage < 1.0) {
        int occupied = 0;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                if (accumulated(pixel + ivec2(dx, dy) * uGapPixels, size).y >= 0.5) ++occupied;
            }
        }
        if (occupied >= INTERIOR_NEIGHBOURS) coverage = 1.0;
    }
    if (coverage <= 0.0) {
        color = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Vertical pass of the normalised Gaussian mean of per-body pressure:
    // independent of how many bodies happen to fall into each pixel.
    vec2 sum = vec2(0.0);
    for (int tap = -SMOOTHING_RADIUS; tap <= SMOOTHING_RADIUS; ++tap) {
        int y = pixel.y + tap * uSampleStep;
        if (y < 0 || y >= size.y) continue;
        sum += smoothingWeight(tap) *
            texelFetch(uHorizontal, ivec2(pixel.x, y), 0).rg;
    }
    float pressure = sum.x / sum.y;
    color = vec4(coverage * pressureColor(pressure), 1.0);
}
