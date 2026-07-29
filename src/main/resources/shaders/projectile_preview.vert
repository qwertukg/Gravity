#version 430 core

uniform vec2 uViewport;
uniform vec2 uCenterPixels;
uniform float uRadiusPixels;
uniform int uDashCount;

noperspective out float dashCoordinatePixels;
noperspective out float pathProgress;

void main() {
    int dash = gl_VertexID / 2;
    int endpoint = gl_VertexID % 2;
    float phase = (float(dash) + 0.5 * float(endpoint)) / float(uDashCount);
    float angle = phase * 6.283185307179586;

    ivec2 instanceOffset = ivec2(
        gl_InstanceID % 3 - 1,
        gl_InstanceID / 3 - 1
    );
    vec2 pointPixels =
        uCenterPixels +
        vec2(cos(angle), sin(angle)) * uRadiusPixels +
        vec2(instanceOffset) * uViewport;

    dashCoordinatePixels = 0.0;
    pathProgress = 0.0;
    gl_Position = vec4(pointPixels / uViewport * 2.0 - 1.0, 0.0, 1.0);
}
