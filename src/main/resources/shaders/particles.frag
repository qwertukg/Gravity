#version 430 core

layout(location = 0) out vec2 accumulation;
flat in float particlePressure;
uniform float uSplatSizePixels;
// Radius of the body's contact disc in pixels.
uniform float uRadiusPixels;

const int SUBSAMPLES = 8;
// Half the pixel diagonal: beyond it from the edge a pixel is fully in or out.
const float HALF_DIAGONAL = 0.70710678;

void main() {
    // gl_PointCoord is measured from the body's exact sub-pixel centre.
    vec2 offsetPixels = (gl_PointCoord - 0.5) * uSplatSizePixels;
    float distance = length(offsetPixels);
    if (distance >= uRadiusPixels + HALF_DIAGONAL) discard;
    // Fraction of this pixel inside the disc, so every body adds exactly its
    // area wherever it sits: small bodies never vanish between pixel centres.
    float coverage = 1.0;
    if (distance > uRadiusPixels - HALF_DIAGONAL) {
        int inside = 0;
        for (int y = 0; y < SUBSAMPLES; ++y) {
            for (int x = 0; x < SUBSAMPLES; ++x) {
                vec2 point = offsetPixels +
                    (vec2(x, y) + 0.5) / float(SUBSAMPLES) - 0.5;
                if (dot(point, point) <= uRadiusPixels * uRadiusPixels) ++inside;
            }
        }
        if (inside == 0) discard;
        coverage = float(inside) / float(SUBSAMPLES * SUBSAMPLES);
    }
    // Additive: (coverage-weighted pressure, coverage) per pixel.
    accumulation = coverage * vec2(particlePressure, 1.0);
}
