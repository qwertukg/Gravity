#version 430 core

layout(location = 0) out vec4 color;

noperspective in float dashCoordinatePixels;
noperspective in float pathProgress;

void main() {
    if (pathProgress < 0.0) discard;
    if (int(floor(dashCoordinatePixels)) % 6 >= 3) discard;
    float opacity = 1.0 - clamp(pathProgress, 0.0, 1.0);
    color = vec4(0.0, 1.0, 0.0, opacity);
}
