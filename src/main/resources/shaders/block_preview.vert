#version 430 core

uniform vec2 uViewport;
uniform vec2 uMinPixels;
uniform vec2 uMaxPixels;
uniform int uHorizontalDashes;
uniform int uVerticalDashes;

noperspective out float dashCoordinatePixels;
noperspective out float pathProgress;

void main() {
    int dash = gl_VertexID / 2;
    int endpoint = gl_VertexID % 2;

    // Edges counter-clockwise from the bottom-left corner; dashes never
    // straddle a corner.
    int halfPerimeter = uHorizontalDashes + uVerticalDashes;
    bool secondHalf = dash >= halfPerimeter;
    if (secondHalf) dash -= halfPerimeter;
    bool vertical = dash >= uHorizontalDashes;
    if (vertical) dash -= uHorizontalDashes;
    int count = vertical ? uVerticalDashes : uHorizontalDashes;

    vec2 start = vertical
        ? vec2(secondHalf ? uMinPixels.x : uMaxPixels.x, secondHalf ? uMaxPixels.y : uMinPixels.y)
        : (secondHalf ? uMaxPixels : uMinPixels);
    vec2 size = uMaxPixels - uMinPixels;
    vec2 edge = vertical ? vec2(0.0, size.y) : vec2(size.x, 0.0);
    if (secondHalf) edge = -edge;
    float phase = (float(dash) + 0.5 * float(endpoint)) / float(count);
    vec2 pointPixels = start + edge * phase;

    dashCoordinatePixels = 0.0;
    pathProgress = 0.0;
    gl_Position = vec4(pointPixels / uViewport * 2.0 - 1.0, 0.0, 1.0);
}
