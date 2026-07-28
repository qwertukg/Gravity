#version 430 core

layout(std430, binding = 0) readonly buffer Trajectory { vec4 trajectory[]; };

noperspective out float dashCoordinatePixels;
noperspective out float pathProgress;

void main() {
    vec4 point = trajectory[gl_VertexID];
    dashCoordinatePixels = point.z;
    pathProgress = point.w;
    gl_Position = vec4(point.xy * 2.0 - 1.0, 0.0, 1.0);
}
