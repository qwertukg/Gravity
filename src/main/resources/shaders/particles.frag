#version 430 core

layout(location = 0) out vec2 accumulation;
flat in float particlePressure;

void main() {
    // Additive: (sum of pressures, body count) per pixel.
    accumulation = vec2(particlePressure, 1.0);
}
