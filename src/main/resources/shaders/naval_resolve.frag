#version 430 core

// Naval battle: the mean colour of the bodies in each pixel over the sky. A
// pixel the lattice of a packed body skips is filled from its neighbours, as
// particles_resolve.frag does.
layout(location = 0) out vec4 color;
uniform sampler2D uAccumulation;
uniform int uGapPixels;
uniform float uSurface;
uniform vec2 uViewport;

const int INTERIOR_NEIGHBOURS = 5;
const vec3 SKY_TOP = vec3(0.015, 0.025, 0.07);
const vec3 SKY_HORIZON = vec3(0.16, 0.22, 0.32);

vec4 accumulated(ivec2 pixel, ivec2 size) {
    return texelFetch(uAccumulation, clamp(pixel, ivec2(0), size - 1), 0);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(uAccumulation, 0);
    vec4 sum = accumulated(pixel, size);
    float coverage = min(sum.a, 1.0);
    if (coverage < 1.0) {
        int occupied = 0;
        vec4 around = vec4(0.0);
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                vec4 neighbour = accumulated(pixel + ivec2(dx, dy) * uGapPixels, size);
                if (neighbour.a >= 0.5) {
                    ++occupied;
                    around += neighbour;
                }
            }
        }
        if (occupied >= INTERIOR_NEIGHBOURS) {
            sum = around;
            coverage = 1.0;
        }
    }
    vec3 background = mix(SKY_HORIZON, SKY_TOP, pow(clamp((gl_FragCoord.y - uSurface) / (uViewport.y - uSurface), 0.0, 1.0), 0.6));
    vec3 bodies = sum.a > 0.0 ? sum.rgb / sum.a : background;
    color = vec4(mix(background, bodies, coverage), 1.0);
}
