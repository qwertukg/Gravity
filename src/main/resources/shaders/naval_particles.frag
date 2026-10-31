#version 430 core

// Naval battle: adds a body's colour times the share of each pixel its disc
// covers (and its opacity), and that share, as particles.frag adds pressure.
// Fire glows: its disc is larger and fades out to its rim.
layout(location = 0) out vec4 accumulation;
flat in vec4 particleColor;
flat in float particleRadius;
// Radius of a plain body's disc, px: a larger one glows.
uniform float uRadiusPixels;

const int SUBSAMPLES = 4;
const float HALF_DIAGONAL = 0.70710678;

void main() {
    float radius = particleRadius;
    vec2 offsetPixels = (gl_PointCoord - 0.5) * (ceil(2.0 * radius) + 2.0);
    float distance = length(offsetPixels);
    if (distance >= radius + HALF_DIAGONAL) discard;
    float coverage = 1.0;
    if (distance > radius - HALF_DIAGONAL) {
        int inside = 0;
        for (int y = 0; y < SUBSAMPLES; ++y) {
            for (int x = 0; x < SUBSAMPLES; ++x) {
                vec2 point = offsetPixels + (vec2(x, y) + 0.5) / float(SUBSAMPLES) - 0.5;
                if (dot(point, point) <= radius * radius) ++inside;
            }
        }
        if (inside == 0) discard;
        coverage = float(inside) / float(SUBSAMPLES * SUBSAMPLES);
    }
    if (radius > uRadiusPixels) coverage *= 1.0 - smoothstep(0.0, radius, distance);
    float weight = coverage * particleColor.a;
    accumulation = vec4(particleColor.rgb * weight, weight);
}
