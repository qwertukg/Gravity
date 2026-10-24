#version 430 core

layout(location = 0) out vec4 color;
in vec4 vertexColor;

void main() {
    color = vertexColor;
}
