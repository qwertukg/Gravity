// Per-body material (mass, Coulomb friction coefficient, solid, unused),
// matching the materials buffer of GravityPipeline. Block bodies are solid.
layout(std430, binding = 8) readonly buffer Materials { vec4 materials[]; };
// Liquid mode: bodies that are not solid are fluid. Fluid pairs are resolved
// by the density constraint of the liquid passes, not as hard contacts, so
// the liquid never locks into a packing.
uniform int uLiquid;

bool fluidBody(uint id) {
    // Branch on the uniform first: without liquid no material is read.
    if (uLiquid == 0) return false;
    return materials[id].z == 0.0;
}

// Every pair with a solid body stays a hard contact, so liquid cannot seep
// through a block however much it is compressed; walls act on every body.
bool hardPair(uint first, uint second) {
    if (uLiquid == 0) return true;
    return materials[first].z != 0.0 || materials[second].z != 0.0;
}

// Each body of a pair takes the other's share of the pair mass, so equal and
// opposite impulses keep momentum; equal masses split a correction in half.
float massShare(vec4 ownMaterial, vec4 otherMaterial) {
    return otherMaterial.x / (ownMaterial.x + otherMaterial.x);
}

float pairFriction(vec4 ownMaterial, vec4 otherMaterial) {
    return sqrt(ownMaterial.y * otherMaterial.y);
}

// Coulomb friction for one side of a contact. The tangential velocity change
// is at most friction times the normal velocity change this pass gives the
// body (removed approach plus pushed-out penetration), and never more than
// its share of the sliding speed, so friction stops sliding but never
// reverses it.
vec2 frictionCorrection(vec2 relativeVelocity, vec2 normal, float relativeNormalSpeed,
    float normalChange, float slidingShare, float friction) {
    vec2 tangentVelocity = relativeVelocity - relativeNormalSpeed * normal;
    float tangentSpeed = length(tangentVelocity);
    if (tangentSpeed <= 0.0) return vec2(0.0);
    float change = min(slidingShare * tangentSpeed, friction * normalChange);
    return tangentVelocity * (-change / tangentSpeed);
}
