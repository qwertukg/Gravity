// Contacts of glued blocks in both collision solvers, after collision_rigid.glsl.
// Needs uRestitutionCoefficient, uWritePressure, uAdaptive and adaptiveState.
//
// Split impulse: the push out of a block moves positions only, and speeds
// change by impulses alone. Turning the push into speed, as loose bodies do,
// would throw a body back as fast as it came, whatever the restitution.
uniform float uBlockRestitution;
// Below this approach speed (px/s) nothing bounces, so resting stays rest.
uniform float uRestingSpeed;
// Position-only move of this body out of blocks and walls.
vec2 rigidShift = vec2(0.0);
// Outward normals of the blocks this loose body touches (reliefMove).
vec2 blockNormals = vec2(0.0);

// Fracture (rigid_fracture.comp). A block's material breaks where an impact
// is faster than [uFractureSpeed], the speed at which the stress of the
// impact, about density x sound speed x approach speed, reaches the
// material's strength; infinite for unbreakable blocks. What the contact
// takes beyond that, (v^2 - vc^2) of the pair's effective mass, pays for
// the crack, each member [uFractureEnergy] times its mass x vc^2.
uniform float uFractureSpeed;
uniform float uFractureEnergy;
layout(std430, binding = 10) writeonly buffer Fractures { vec4 fractures[]; };
// The hardest impact on this member this pass: its direction into the
// member's block, the crack it pays for (members) and its approach speed.
vec4 fractureRecord = vec4(0.0);

void recordFracture(vec2 direction, float speed, float members) {
    if (speed > fractureRecord.w) fractureRecord = vec4(direction, fractureRecord.z, speed);
    fractureRecord.z += members;
}

// Written only when the member was struck: the rigid projection reads and
// clears it right after the pass, in the blocks marked struck.
void storeFracture(uint id) {
    if (fractureRecord.w <= 0.0) return;
    fractures[id] = fractureRecord;
    groups[groupOf(id)].scalars.w = 1.0;
}


// Impulses of the contacts of a block share it (mass splitting) and reach the
// speed they aim for over a few passes. Restitution therefore aims at the
// approach speed the pair had when the impact began, remembered per pair
// (rigid_layout.glsl), not at what is left of it.
float restitutionTarget(int key, float normalSpeed, float restitution) {
    float impact = max(float(impacts[key]) / IMPACT_SCALE, -normalSpeed);
    if (-normalSpeed > uRestingSpeed) atomicMax(nextImpacts[key], int(-normalSpeed * IMPACT_SCALE));
    return impact > uRestingSpeed ? restitution * impact : 0.0;
}


// Contacts solved side by side converge over passes, so a pressed or fast
// body can still end up with its centre inside a block. The guard puts it
// back on the block's outline at once, position only: a position carries no
// momentum, and it is not turned into speed. Each body keeps the two blocks
// it is deepest in: the move out of each and the block's mass.
int guardGroups[2] = int[2](-1, -1);
vec2 guardShifts[2] = vec2[2](vec2(0.0), vec2(0.0));
float guardMasses[2] = float[2](0.0, 0.0);

void addGuard(int group, vec2 shift, float mass) {
    for (int k = 0; k < 2; k++) {
        if (guardGroups[k] == group) {
            if (length(shift) > length(guardShifts[k])) guardShifts[k] = shift;
            return;
        }
    }
    int slot = guardGroups[0] < 0 ? 0 : guardGroups[1] < 0 ? 1
        : length(guardShifts[0]) < length(guardShifts[1]) ? 0 : 1;
    if (guardGroups[slot] >= 0 && length(shift) <= length(guardShifts[slot])) return;
    guardGroups[slot] = group;
    guardShifts[slot] = shift;
    guardMasses[slot] = mass;
}

// Refines a guard move until the body sits on the block's outline: the
// shape's depths inside are lower bounds (GlueShape), so one step falls short.
vec2 settle(int group, vec2 positionPixels, vec2 shift, float driftDt) {
    vec2 center;
    vec2 rotation;
    groupPose(group, driftDt, center, rotation);
    // Loose bodies are the only ones guarded this way; liquid ones lower down.
    float outline = outlineValue() - (uLiquid != 0 ? RIPPLE_DIAMETERS * uDiameterPixels : 0.0);
    for (int step = 0; step < 4; step++) {
        vec2 gradient;
        float value = sampleShape(group, unrotate(rotation, rigidOffset(positionPixels + shift - center)), gradient);
        float missing = outline - value;
        if (missing <= 0.001 * uDiameterPixels || dot(gradient, gradient) <= 1e-12) break;
        shift += rotate(rotation, normalize(gradient)) * missing;
    }
    return shift;
}

// The guard's move of this body. A body wedged between two blocks, or
// pushed out of a block into a wall, cannot move out: the blocks move
// instead, apart by their masses, or off the body against the wall.
vec2 resolveGuards(vec2 positionPixels, float driftDt) {
    if (guardGroups[0] < 0) return vec2(0.0);
    if (guardGroups[1] >= 0 && dot(guardShifts[0], guardShifts[1]) < 0.0) {
        float gap = length(guardShifts[0]) + length(guardShifts[1]);
        float total = guardMasses[0] + guardMasses[1];
        pushGroup(guardGroups[0], -normalize(guardShifts[0]) * (gap * guardMasses[1] / total));
        pushGroup(guardGroups[1], -normalize(guardShifts[1]) * (gap * guardMasses[0] / total));
        return vec2(0.0);
    }
    int deepest = guardGroups[1] >= 0 && length(guardShifts[1]) > length(guardShifts[0]) ? 1 : 0;
    vec2 shift = settle(guardGroups[deepest], positionPixels, guardShifts[deepest], driftDt);
    if (uBounded != 0) {
        // What a wall stops of the move, the block makes room for.
        float radius = 0.5 * uDiameterPixels;
        vec2 target = positionPixels + shift;
        vec2 blocked = max(vec2(radius) - target, vec2(0.0)) - max(target - (uViewport - radius), vec2(0.0));
        if (blocked != vec2(0.0)) {
            supportGroup(guardGroups[deepest], blocked);
            shift += blocked;
        }
    }
    return shift;
}

// A member of a crowd solves a sample of its loose contacts
// (collision_crowd.glsl), each standing for this many; its loose partners
// solve all theirs, so its block gets their impulses as many times as it
// should on average.
float memberSample = 1.0;

// This body's depth in the last block it probed and the normal out of it:
// the same for every member of that block it touches.
int probedGroup = -1;
float probedPenetration = 0.0;
vec2 probedNormal = vec2(0.0);

// A pair with at least one member. The shape is the member's block; between
// two blocks, the lower-numbered one's. Both bodies of the pair evaluate the
// same expressions in the same order, so their impulses are equal and opposite.
void rigidContact(uint id, uint otherId, vec4 otherMaterial, vec2 ownPixels, vec2 ownVelocity, vec2 otherPixels,
    vec2 otherVelocity, uint ownContacts, uint otherContacts, float driftDt,
    float effectiveSubstepDt, inout vec2 positionCorrection,
    inout vec2 velocityCorrection, inout float pressure) {
    int ownGroup = groupOf(ownMaterial);
    int otherGroup = groupOf(otherMaterial);
    bool otherShape = ownGroup < 0 || (otherGroup >= 0 && otherGroup < ownGroup);
    int shapeGroup = otherShape ? otherGroup : ownGroup;
    int probeGroup = otherShape ? ownGroup : otherGroup;
    uint probe = otherShape ? id : otherId;
    uint member = otherShape ? otherId : id;
    vec2 probePixels = otherShape ? ownPixels : otherPixels;
    vec2 probeVelocity = otherShape ? ownVelocity : otherVelocity;
    vec2 memberVelocity = otherShape ? otherVelocity : ownVelocity;
    uint probeContacts = otherShape ? ownContacts : otherContacts;

    // A fluid body is held off by the liquid; the shape only stops its centre.
    // Liquid rests in the dips between the members of a face, which the
    // smoothed shape fills up to the outline: its guard stops the centre that
    // much lower (GlueShape.RIPPLE_DIAMETERS), or it would jostle the liquid
    // resting there pass after pass.
    float outline = outlineValue();
    vec4 probeMaterial = otherShape ? ownMaterial : otherMaterial;
    vec4 memberMaterial = otherShape ? otherMaterial : ownMaterial;
    bool fluidProbe = fluidMaterial(probeMaterial);
    float guardLevel = fluidProbe ? outline - RIPPLE_DIAMETERS * uDiameterPixels : outline;
    float probeRadius = fluidProbe ? guardLevel : 0.5 * uDiameterPixels;
    // Blocks within an impact meet a little beyond touching (meetsWall).
    float slop = probeGroup >= 0 && impacts[contactKey(shapeGroup, probeGroup)] != 0
        ? SPECULATIVE_DIAMETERS * uDiameterPixels : 0.0;
    vec2 normal;
    float penetration;
    if (otherShape && shapeGroup == probedGroup) {
        penetration = probedPenetration;
        normal = probedNormal;
    } else {
        penetration = shapeContact(shapeGroup, probePixels, probeVelocity, probeRadius, slop, driftDt, normal);
        if (otherShape) {
            probedGroup = shapeGroup;
            probedPenetration = penetration;
            probedNormal = normal;
        }
    }
    if (normal == vec2(0.0) || penetration <= -slop) return;
    if (otherShape && probeGroup < 0) blockNormals += normal;
    vec2 point = probePixels - normal * probeRadius;
    // Inverse masses along a direction with mass splitting: a loose body
    // splits by its own contact count, a block by its block's.
    float probeShare = probeGroup >= 0 ? partnerShare(probeGroup, shapeGroup) / probeMaterial.x
        : float(max(probeContacts, 1u)) / probeMaterial.x;
    float shapeShare = partnerShare(shapeGroup, probeGroup) / memberMaterial.x;
    float response = shapeShare * rigidResponse(shapeGroup, point, normal, driftDt) +
        (probeGroup >= 0 ? probeShare * rigidResponse(probeGroup, point, normal, driftDt) : probeShare);

    float side = otherShape ? 1.0 : -1.0;
    float ownMass = otherShape ? probeMaterial.x : memberMaterial.x;
    float ownSample = !otherShape && probeGroup < 0 ? memberSample : 1.0;
    vec2 ownNormal = normal * side;
    float impulse = max(penetration, 0.0) / response;
    // The guard (above) for a body whose centre is beyond the outline.
    if (otherShape && probeGroup >= 0) {
        // Between blocks the guard moves the blocks themselves, apart by
        // their masses; a member alone would only nudge its block.
        float beyond = penetration - probeRadius + guardLevel;
        if (beyond > 0.0) {
            float probeMass = probeMaterial.x * groups[probeGroup].scalars.z;
            float shapeMass = memberMaterial.x * groups[shapeGroup].scalars.z;
            pushGroup(probeGroup, normal * (beyond * shapeMass / (probeMass + shapeMass)));
            pushGroup(shapeGroup, -normal * (beyond * probeMass / (probeMass + shapeMass)));
        }
    } else if (otherShape) {
        float regular = fluidProbe ? 0.0 : impulse / ownMass;
        float beyond = penetration - probeRadius + guardLevel - regular;
        if (beyond > 0.0) addGuard(shapeGroup, normal * beyond, memberMaterial.x * groups[shapeGroup].scalars.z);
    }
    // The liquid presses fluid against a block on its own: for a fluid body
    // the guard is the whole contact.
    if (!fluidProbe) rigidShift += ownNormal * (impulse * ownSample / ownMass);

    vec2 relativeVelocity = probeVelocity - memberVelocity;
    float normalSpeed = dot(relativeVelocity, normal);
    // A block keeps its own restitution with blocks; a loose body gets the
    // larger of its own and the block's, at once. Liquid never bounces.
    float velocityImpulse = 0.0;
    if (probeGroup >= 0) {
        int key = contactKey(shapeGroup, probeGroup);
        float target = restitutionTarget(key, normalSpeed, uBlockRestitution);
        if (normalSpeed < target) velocityImpulse = (target - normalSpeed) / response;
        atomicMax(contactStates[key], normalSpeed < target ? CONTACT_PUSHING : CONTACT_TOUCHING);
    } else if (normalSpeed < 0.0) {
        float restitution = fluidProbe || normalSpeed > -uRestingSpeed ? 0.0
            : max(uRestitutionCoefficient, uBlockRestitution);
        velocityImpulse = -(1.0 + restitution) * normalSpeed / response;
    }
    velocityCorrection += ownNormal * (velocityImpulse * ownSample / ownMass);
    // A struck member: the impact runs into its block along ownNormal. Two
    // blocks share the energy of their contact; liquid breaks nothing.
    if (ownGroup >= 0 && !fluidProbe && -normalSpeed > uFractureSpeed) {
        float energy = (normalSpeed * normalSpeed - uFractureSpeed * uFractureSpeed) / response;
        if (probeGroup >= 0) energy *= 0.5;
        recordFracture(ownNormal, -normalSpeed, energy * ownSample * uFractureEnergy / ownMass);
    }
    // Coulomb friction: the tangential impulse stops the sliding at most, and
    // never exceeds friction times the normal impulse.
    float friction = pairFriction(probeMaterial, memberMaterial);
    vec2 sliding = relativeVelocity - normalSpeed * normal;
    float slidingSpeed = length(sliding);
    if (friction > 0.0 && velocityImpulse > 0.0 && slidingSpeed > 0.0) {
        vec2 tangent = sliding / slidingSpeed;
        float tangentResponse = shapeShare * rigidResponse(shapeGroup, point, tangent, driftDt) +
            (probeGroup >= 0 ? probeShare * rigidResponse(probeGroup, point, tangent, driftDt) : probeShare);
        float tangentImpulse = min(slidingSpeed / tangentResponse, friction * velocityImpulse);
        velocityCorrection -= tangent * (side * tangentImpulse * ownSample / ownMass);
    }
    if (uWritePressure != 0) {
        if (uAdaptive != 0) atomicMax(adaptiveState[1], floatBitsToUint(penetration));
        pressure += 0.5 * ownSample / float(max(max(ownContacts, otherContacts), 1u)) * (
            penetration / effectiveSubstepDt + max(-normalSpeed, 0.0)
        );
    }
}

// A member against a wall. The wall is immovable, so the member takes the
// whole correction its block needs, split over the block's contacts, with
// the block's restitution and its own friction.
void rigidWallContact(uint id, int wall, vec2 positionPixels, float distance, vec2 normal, vec2 velocityPixels,
    float pressureWeight, float friction, float driftDt, float effectiveSubstepDt,
    inout vec2 velocityCorrection, inout float pressure) {
    int group = groupOf(id);
    if (!meetsWall(group, wall, distance)) return;
    float penetration = 0.5 * uDiameterPixels - distance;
    int key = group * CONTACT_PARTNERS + MAXIMUM_GROUPS + wall;
    vec2 point = positionPixels - normal * (0.5 * uDiameterPixels);
    float contacts = partnerShare(group, MAXIMUM_GROUPS + wall);
    if (penetration > 0.0) rigidShift += normal * (penetration / (contacts * rigidResponse(group, point, normal, driftDt)));
    float normalSpeed = dot(velocityPixels, normal);
    float target = restitutionTarget(key, normalSpeed, uBlockRestitution);
    // Speed changes of the member per unit of its mass.
    float normalChange = 0.0;
    if (normalSpeed < target) normalChange = (target - normalSpeed) / (contacts * rigidResponse(group, point, normal, driftDt));
    atomicMax(contactStates[key], normalSpeed < target ? CONTACT_PUSHING : CONTACT_TOUCHING);
    velocityCorrection += normal * normalChange;
    // Against a wall the member's share of its block is the effective mass.
    if (-normalSpeed > uFractureSpeed) {
        float energy = (normalSpeed * normalSpeed - uFractureSpeed * uFractureSpeed) /
            (contacts * rigidResponse(group, point, normal, driftDt));
        recordFracture(normal, -normalSpeed, energy * uFractureEnergy);
    }
    vec2 sliding = velocityPixels - normalSpeed * normal;
    float slidingSpeed = length(sliding);
    if (friction > 0.0 && normalChange > 0.0 && slidingSpeed > 0.0) {
        vec2 tangent = sliding / slidingSpeed;
        float stop = slidingSpeed / (contacts * rigidResponse(group, point, tangent, driftDt));
        velocityCorrection -= tangent * min(stop, friction * normalChange);
    }
    if (uWritePressure != 0) {
        pressure += pressureWeight * (max(penetration, 0.0) / effectiveSubstepDt + max(-normalSpeed, 0.0));
    }
}
