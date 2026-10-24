#version 430 core

layout(location = 0) out vec2 blurred;
uniform sampler2D uAccumulation;

#include "particles_smoothing.glsl"

void main() {
    // Horizontal pass of a normalised convolution: (sum of pressures, body
    // count) are blurred together and divided only after the vertical pass.
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int lastColumn = textureSize(uAccumulation, 0).x - 1;
    vec2 sum = vec2(0.0);
    for (int dx = -SMOOTHING_RADIUS; dx <= SMOOTHING_RADIUS; ++dx) {
        int x = pixel.x + dx;
        if (x < 0 || x > lastColumn) continue;
        sum += smoothingWeight(dx) *
            texelFetch(uAccumulation, ivec2(x, pixel.y), 0).rg;
    }
    blurred = sum;
}
