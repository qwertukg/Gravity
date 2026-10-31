#version 430 core

#include "naval_layout.glsl"
#include "rigid_layout.glsl"

// Naval battle: the colour of every body from what it is and its state.
// - Water: blue, darker with depth below the surface, whitened by its foam.
// - Ships: grey, lighter at their faces and darker deeper in the hull (the
//   block's distance field), tinted blue under water.
// - Barrels: gunmetal. Missiles: white. Shells: brass. Grains broken off: dark grey.
// - Hot bodies glow by their age: fire from white through yellow, orange and
//   red to smoke that fades; fragments from yellow heat through red to grey.
layout(std430, binding = 0) readonly buffer Bodies { vec4 bodies[]; };
layout(std430, binding = 1) readonly buffer Materials { vec4 materials[]; };
layout(std430, binding = 2) readonly buffer Rigid {
    RigidGroup groups[MAXIMUM_GROUPS];
    int contactMemory[5 * CONTACT_KEYS];
    float shapes[];
};

flat out vec4 particleColor;
flat out float particleRadius;
// Radius of a body's disc (px), and of the glow of fire.
uniform float uRadiusPixels;
uniform float uFireRadiusPixels;
uniform vec2 uViewport;
// Height of the pool's surface at rest and its depth, px.
uniform float uSurface;
uniform float uDepth;
uniform float uStep;
uniform float uDt;
uniform float uFireLife;
uniform float uFireMass;
uniform float uFragmentCool;
const int BARRELS = 8;
uniform int uBarrels;
uniform int uBarrel[BARRELS];
const int MISSILES = 2;
uniform int uMissiles;
uniform int uMissile[MISSILES];

const vec3 SURFACE_WATER = vec3(0.10, 0.46, 0.78);
const vec3 SPLASH_WATER = vec3(0.30, 0.66, 0.92);
const vec3 DEEP_WATER = vec3(0.01, 0.07, 0.26);
const vec3 FOAM = vec3(0.92, 0.96, 1.0);
// Under the surface foam is bubbles: fainter, and the water's colour shows.
const vec3 BUBBLES = vec3(0.55, 0.76, 0.95);
const vec3 HULL_FACE = vec3(0.70, 0.72, 0.74);
const vec3 HULL_CORE = vec3(0.30, 0.32, 0.35);
const vec3 UNDERWATER = vec3(0.18, 0.36, 0.54);
const vec3 GUNMETAL = vec3(0.50, 0.53, 0.57);
const vec3 BRASS = vec3(0.95, 0.78, 0.40);
const vec3 MISSILE = vec3(0.90, 0.91, 0.93);
const vec3 DEBRIS = vec3(0.34, 0.35, 0.37);
const vec3 SMOKE = vec3(0.22, 0.21, 0.22);

// Distance field of a block in its rest frame: negative inside.
float shapeValue(int group, vec2 local) {
    vec4 shape = groups[group].shape;
    ivec4 grid = groups[group].grid;
    vec2 u = (local - shape.xy) / shape.z;
    vec2 last = vec2(grid.yz - 1);
    if (any(lessThan(u, vec2(0.0))) || any(greaterThanEqual(u, last))) return shape.w;
    ivec2 cell = ivec2(u);
    vec2 f = u - vec2(cell);
    int base = grid.x + cell.x + cell.y * grid.y;
    return mix(mix(shapes[base], shapes[base + 1], f.x), mix(shapes[base + grid.y], shapes[base + grid.y + 1], f.x), f.y);
}

// Blackbody-like ramp of fire, by age (s): white, yellow, orange, red, smoke.
vec4 fireColor(float age) {
    vec3 color;
    if (age < 0.08) color = mix(vec3(1.0, 0.98, 0.85), vec3(1.0, 0.88, 0.35), age / 0.08);
    else if (age < 0.25) color = mix(vec3(1.0, 0.88, 0.35), vec3(1.0, 0.52, 0.08), (age - 0.08) / 0.17);
    else if (age < 0.5) color = mix(vec3(1.0, 0.52, 0.08), vec3(0.80, 0.16, 0.03), (age - 0.25) / 0.25);
    else if (age < 0.8) color = mix(vec3(0.80, 0.16, 0.03), SMOKE, (age - 0.5) / 0.3);
    else color = SMOKE;
    float alpha = 1.0 - smoothstep(0.6 * uFireLife, uFireLife, age);
    return vec4(color, alpha);
}

// Heat of a fragment cooling from yellow through red to the grey of debris.
vec3 fragmentColor(float age) {
    float heat = 1.0 - clamp(age / uFragmentCool, 0.0, 1.0);
    if (heat > 0.6) return mix(vec3(1.0, 0.45, 0.06), vec3(1.0, 0.85, 0.4), (heat - 0.6) / 0.4);
    if (heat > 0.25) return mix(vec3(0.6, 0.08, 0.02), vec3(1.0, 0.45, 0.06), (heat - 0.25) / 0.35);
    return mix(DEBRIS, vec3(0.6, 0.08, 0.02), heat / 0.25);
}

vec4 bodyColor(vec4 body, vec4 material) {
    vec2 position = body.xy * uViewport;
    float depth = uSurface - position.y;
    if (material.z == 0.0) {
        vec3 water = depth < 0.0
            ? mix(SURFACE_WATER, SPLASH_WATER, clamp(-depth / 30.0, 0.0, 1.0))
            : mix(SURFACE_WATER, DEEP_WATER, clamp(depth / uDepth, 0.0, 1.0));
        float under = smoothstep(1.0, 12.0, depth);
        vec3 foam = mix(FOAM, BUBBLES, under);
        return vec4(mix(water, foam, clamp(material.w, 0.0, 1.0) * mix(1.0, 0.45, under)), 1.0);
    }
    if (material.w >= 0.0) {
        int group = int(material.w);
        for (int index = 0; index < uBarrels; index++) {
            if (uBarrel[index] == group) return vec4(GUNMETAL, 1.0);
        }
        for (int index = 0; index < uMissiles; index++) {
            if (uMissile[index] == group) return vec4(MISSILE, 1.0);
        }
        RigidGroup rigid = groups[group];
        vec2 offset = position - rigid.frame.zw;
        vec2 rotation = rigid.motion.zw;
        vec2 local = vec2(rotation.x * offset.x + rotation.y * offset.y, -rotation.y * offset.x + rotation.x * offset.y);
        float inside = clamp(-shapeValue(group, local) / 7.0, 0.0, 1.0);
        vec3 hull = mix(HULL_FACE, HULL_CORE, sqrt(inside));
        return vec4(mix(hull, UNDERWATER, 0.45 * smoothstep(0.0, 25.0, depth)), 1.0);
    }
    if (material.w == PARKED_TAG) return vec4(0.0);
    if (isHot(material)) {
        float age = hotAge(material, uStep, uDt);
        // Fire bodies cool at their own pace (naval_detonate.comp): the ball has a hot core and a cooler rim.
        float pace = mix(0.7, 1.5, clamp(material.x / uFireMass - 0.5, 0.0, 1.0));
        return material.x < FIRE_MASS_LIMIT ? fireColor(age * pace) : vec4(fragmentColor(age), 1.0);
    }
    if (shellOf(material) >= 0) return vec4(BRASS, 1.0);
    return vec4(DEBRIS, 1.0);
}

void main() {
    vec4 body = bodies[gl_VertexID];
    vec4 material = materials[gl_VertexID];
    particleColor = bodyColor(body, material);
    // Hot gas glows beyond its body.
    particleRadius = isHot(material) && material.x < FIRE_MASS_LIMIT ? uFireRadiusPixels : uRadiusPixels;
    // Invisible bodies are left out of the raster altogether.
    gl_Position = particleColor.a > 0.0 ? vec4(body.xy * 2.0 - 1.0, 0.0, 1.0) : vec4(2.0, 2.0, 0.0, 1.0);
    gl_PointSize = ceil(2.0 * particleRadius) + 2.0;
}
