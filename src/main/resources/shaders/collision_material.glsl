// Per-body material (mass, Coulomb friction coefficient, solid, block),
// matching the materials buffer of GravityPipeline. Block bodies are solid
// and name their glued block; a body broken off a block stays solid and
// names none (-1): a loose grain of the block's material.
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

// The RIGID variant of a collision pass runs while there are glued blocks;
// without them every pass is the plain one, with none of this code: drivers
// may load materials ahead of a branch they then skip.
#ifdef RIGID
// A body of a glued block; a grain broken off one is solid but no member.
bool isMember(vec4 material) {
    return material.z != 0.0 && material.w >= 0.0;
}

// The glued block of a body, or -1 for a loose body.
int groupOf(uint id) {
    vec4 material = materials[id];
    return isMember(material) ? int(material.w) : -1;
}

// A pair with a member of a block: a contact with the block's rigid shape.
bool rigidPair(uint first, uint second) {
    return isMember(materials[first]) || isMember(materials[second]);
}

// Liquid mode: the liquid passes keep fluid off a block by its pressure, and
// a hard contact is only the guard that never lets a fluid centre into the
// block's closed outline: it starts nearer a member than the fill that closes
// it (GlueShape.FILL_DIAMETERS), not at the touching distance.
const float FLUID_REACH_DIAMETERS = 0.6;

// Squared centre distance below which a hard pair is in contact
// (collision_rigid.glsl).
float contactReachSquared(uint first, uint second);
#else
float contactReachSquared(uint first, uint second) {
    return uDiameterPixels * uDiameterPixels;
}
#endif

// The top bit of a body's contact count says that it touches a block
// (RIGID passes). A block holds a body off position-only (split impulse), so
// the body cannot move apart from the bodies it overlaps there: their push
// turned into speed, as loose bodies take it, would only build up, pass after
// pass, wherever bodies crowd into a crater or a crack, until they burst
// out. A pair where either body is held moves apart by position alone; both
// bodies read both bits, so the pair stays equal and opposite.
const uint HELD_BIT = 0x80000000u;

uint contactsOf(uint count) {
    return count & ~HELD_BIT;
}

bool heldPair(uint firstCount, uint secondCount) {
    return ((firstCount | secondCount) & HELD_BIT) != 0u;
}

// Every pair with a solid body stays a hard contact, so liquid cannot seep
// through a block however much it is compressed; walls act on every body.
// Members of one block never touch: the block holds them.
bool hardPair(uint first, uint second) {
#ifdef RIGID
    vec4 firstMaterial = materials[first];
    vec4 secondMaterial = materials[second];
    if (isMember(firstMaterial) && isMember(secondMaterial) && firstMaterial.w == secondMaterial.w) return false;
#endif
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
