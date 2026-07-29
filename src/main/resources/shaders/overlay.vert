#version 430 core

layout(location = 0) in vec2 position;

uniform vec2 uViewport;

void main() {
    vec2 pixel = vec2(8.0, 8.0) + position;
    vec2 clip = vec2(
        pixel.x * 2.0 / uViewport.x - 1.0,
        1.0 - pixel.y * 2.0 / uViewport.y
    );
    gl_Position = vec4(clip, 0.0, 1.0);
}
