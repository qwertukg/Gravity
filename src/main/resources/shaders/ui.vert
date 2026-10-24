#version 430 core

layout(location = 0) in vec2 position;
layout(location = 1) in vec4 color;

uniform vec2 uViewport;

out vec4 vertexColor;

void main() {
    // Window pixels with the origin in the top-left corner.
    vertexColor = color;
    gl_Position = vec4(
        position.x * 2.0 / uViewport.x - 1.0,
        1.0 - position.y * 2.0 / uViewport.y,
        0.0,
        1.0
    );
}
