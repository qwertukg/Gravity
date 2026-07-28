#version 430 core

layout(location = 0) out vec4 color;

noperspective in float pathDistancePixels;
noperspective in float pathProgress;

void main() {
    if (int(floor(pathDistancePixels)) % 6 >= 3) discard;
    float opacity = 1.0 - clamp(pathProgress, 0.0, 1.0);
    color = vec4(1.0, 0.85, 0.1, opacity);
}
