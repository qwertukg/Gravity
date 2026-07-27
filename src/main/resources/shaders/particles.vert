#version 430 core

layout(std430, binding = 0) readonly buffer Bodies { vec4 bodies[]; };

void main() {
    vec2 position = bodies[gl_VertexID].xy * 2.0 - 1.0;
    gl_Position = vec4(position, 0.0, 1.0);
    gl_PointSize = 1.0;
}
