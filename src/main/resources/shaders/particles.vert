#version 430 core

layout(std430, binding = 0) readonly buffer Bodies { vec4 bodies[]; };
layout(std430, binding = 1) readonly buffer ActiveFlags { int activeFlags[]; };

uniform float uViewportAspect;

void main() {
    if (activeFlags[gl_VertexID] == 0) {
        gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
        gl_PointSize = 1.0;
        return;
    }
    vec2 position = bodies[gl_VertexID].xy * 2.0 - 1.0;
    position.y *= uViewportAspect;
    gl_Position = vec4(position, 0.0, 1.0);
    gl_PointSize = 1.0;
}
