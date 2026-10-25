// Contacts of glued blocks in both collision solvers, after collision_rigid.glsl.
// Needs uRestitutionCoefficient, uWritePressure, uAdaptive and adaptiveState.

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
    float outline = outlineValue();
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
        float radius = 0.5 * uDiameterPixels;
        vec2 target = positionPixels + shift;
        if (any(lessThan(target, vec2(radius))) || any(greaterThan(target, uViewport - radius))) {
            pushGroup(guardGroups[deepest], -shift);
            return vec2(0.0);
        }
    }
    return shift;
}

// A pair with at least one member. The shape is the member's block; between
// two blocks, the lower-numbered one's. Both bodies of the pair evaluate the
// same expressions in the same order, so their impulses are equal and opposite.
void rigidContact(uint id, uint otherId, vec2 ownPixels, vec2 ownVelocity, vec2 otherPixels,
    vec2 otherVelocity, uint ownContacts, uint otherContacts, float driftDt,
    float effectiveSubstepDt, inout vec2 positionCorrection,
    inout vec2 velocityCorrection, inout float pressure) {
    int ownGroup = groupOf(id);
    int otherGroup = groupOf(otherId);
    bool otherShape = ownGroup < 0 || (otherGroup >= 0 && otherGroup < ownGroup);
    int shapeGroup = otherShape ? otherGroup : ownGroup;
    int probeGroup = otherShape ? ownGroup : otherGroup;
    uint probe = otherShape ? id : otherId;
    uint member = otherShape ? otherId : id;
    vec2 probePixels = otherShape ? ownPixels : otherPixels;
    vec2 probeVelocity = otherShape ? ownVelocity : otherVelocity;
    vec2 memberVelocity = otherShape ? otherVelocity : ownVelocity;
    uint probeContacts = otherShape ? ownContacts : otherContacts;

    // A fluid body is held off by the liquid; the shape only stops its centre
    // at the outline.
    float outline = outlineValue();
    bool fluidProbe = uLiquid != 0 && probeGroup < 0;
    float probeRadius = fluidProbe ? outline : 0.5 * uDiameterPixels;
    vec2 normal;
    float penetration = shapeContact(shapeGroup, probePixels, probeVelocity, probeRadius, driftDt, normal);
    if (penetration <= 0.0) return;
    vec2 point = probePixels - normal * probeRadius;
    vec4 probeMaterial = materials[probe];
    vec4 memberMaterial = materials[member];
    // Inverse masses with mass splitting: a loose body splits by its own
    // contact count, a block by its block's.
    float probeResponse = probeGroup >= 0
        ? groupContacts(probeGroup) * rigidResponse(probeGroup, point, normal, driftDt) / probeMaterial.x
        : float(max(probeContacts, 1u)) / probeMaterial.x;
    float shapeResponse = groupContacts(shapeGroup) * rigidResponse(shapeGroup, point, normal, driftDt) / memberMaterial.x;
    float response = probeResponse + shapeResponse;

    float side = otherShape ? 1.0 : -1.0;
    float ownMass = otherShape ? probeMaterial.x : memberMaterial.x;
    vec2 ownNormal = normal * side;
    float impulse = penetration / response;
    // The guard (above) for a body whose centre is beyond the outline.
    if (otherShape && probeGroup >= 0) {
        // Between blocks the guard moves the blocks themselves, apart by
        // their masses; a member alone would only nudge its block.
        float beyond = penetration - probeRadius + outline;
        if (beyond > 0.0) {
            float probeMass = probeMaterial.x * groups[probeGroup].scalars.z;
            float shapeMass = memberMaterial.x * groups[shapeGroup].scalars.z;
            pushGroup(probeGroup, normal * (beyond * shapeMass / (probeMass + shapeMass)));
            pushGroup(shapeGroup, -normal * (beyond * probeMass / (probeMass + shapeMass)));
        }
    } else if (otherShape) {
        float regular = fluidProbe ? 0.0 : impulse / ownMass;
        float beyond = penetration - probeRadius + outline - regular;
        if (beyond > 0.0) addGuard(shapeGroup, normal * beyond, memberMaterial.x * groups[shapeGroup].scalars.z);
    }
    // The liquid presses fluid against a block on its own: for a fluid body
    // the guard is the whole contact, which would otherwise blast the liquid
    // that carries the block away from under it.
    if (!fluidProbe) positionCorrection += ownNormal * (impulse / ownMass);

    vec2 relativeVelocity = probeVelocity - memberVelocity;
    float normalSpeed = dot(relativeVelocity, normal);
    // e=0: the approaching normal velocities meet.
    float velocityImpulse = normalSpeed < 0.0
        ? -(1.0 + uRestitutionCoefficient) * normalSpeed / response : 0.0;
    velocityCorrection += ownNormal * (velocityImpulse / ownMass);
    float friction = pairFriction(probeMaterial, memberMaterial);
    if (friction > 0.0) {
        velocityCorrection += frictionCorrection(relativeVelocity * side, ownNormal, normalSpeed,
            (velocityImpulse + (fluidProbe ? 0.0 : impulse / effectiveSubstepDt)) / ownMass,
            1.0 / (response * ownMass), friction);
    }
    if (uWritePressure != 0) {
        if (uAdaptive != 0) atomicMax(adaptiveState[1], floatBitsToUint(penetration));
        pressure += 0.5 / float(max(max(ownContacts, otherContacts), 1u)) * (
            penetration / effectiveSubstepDt + max(-normalSpeed, 0.0)
        );
    }
}

// Correction weight of a member's wall contact: the wall is immovable, so the
// member takes the whole correction its block would need, split over the
// block's contacts.
float rigidWallWeight(uint id, vec2 positionPixels, vec2 normal, float driftDt) {
    int group = groupOf(id);
    vec2 point = positionPixels - normal * (0.5 * uDiameterPixels);
    return 1.0 / (groupContacts(group) * rigidResponse(group, point, normal, driftDt));
}
