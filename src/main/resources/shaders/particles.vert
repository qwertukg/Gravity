#version 430 core

layout(std430, binding = 0) readonly buffer Bodies { vec4 bodies[]; };
layout(std430, binding = 1) readonly buffer Pressures { float pressures[]; };

flat out float particlePressure;
uniform float uMaximumPressure;
// Integer side of the rasterized square; a round body's disc fits inside it.
uniform float uSplatSizePixels;

void main() {
    vec2 position = bodies[gl_VertexID].xy * 2.0 - 1.0;
    particlePressure = clamp(
        pressures[gl_VertexID] / uMaximumPressure,
        0.0,
        1.0
    );
    gl_Position = vec4(position, 0.0, 1.0);
    gl_PointSize = uSplatSizePixels;
}
