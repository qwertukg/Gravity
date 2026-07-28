#version 430 core

layout(location = 0) out vec4 color;
flat in float particlePressure;

vec3 hsvToRgb(vec3 hsv) {
    vec3 p = abs(fract(hsv.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) *
        6.0 - 3.0);
    return hsv.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), hsv.y);
}

void main() {
    float pressure = particlePressure;
    // Long HSV path: cyan -> green -> yellow -> orange -> red.
    float hue = mix(180.0 / 360.0, 0.0, pressure);
    color = vec4(hsvToRgb(vec3(hue, 1.0, 1.0)), 1.0);
}
