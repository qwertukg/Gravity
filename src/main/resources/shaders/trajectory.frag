#version 430 core

layout(location = 0) out vec4 color;

noperspective in float dashCoordinatePixels;
noperspective in float pathProgress;

uniform float uMinimumDashPixels;

void main() {
    if (pathProgress < 0.0) discard;
    if (
        mod(
            floor(dashCoordinatePixels),
            2.0 * uMinimumDashPixels
        ) >= uMinimumDashPixels
    ) discard;
    float opacity = 1.0 - clamp(pathProgress, 0.0, 1.0);
    color = vec4(0.0, 1.0, 0.0, opacity);
}
