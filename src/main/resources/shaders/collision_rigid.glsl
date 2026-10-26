// Glued blocks are rigid bodies in the collision passes, matching RigidBlocks.kt
// and GlueShape.kt. A contact with a member is a contact with the block's
// solid shape: its normal and depth come from the block's distance field, and
// the correction is split by the block's rigid response (mass, inertia, arm),
// not by the mass of one member. Each side of a pair still applies only its
// own equal and opposite share, to its own body; the rigid projection after
// the pass turns what the members took into the motion of their block.
//
// Needs collision_material.glsl, uViewport, uBounded and uDiameterPixels.
#include "rigid_layout.glsl"
layout(std430, binding = 9) buffer Rigid {
    RigidGroup groups[MAXIMUM_GROUPS];
    // Approach speed at the start of the current impact, as of the last pass;
    // the largest seen this pass; how the pair met this pass (CONTACT_*);
    // passes since it last met.
    int impacts[CONTACT_KEYS];
    int nextImpacts[CONTACT_KEYS];
    int contactStates[CONTACT_KEYS];
    int idlePasses[CONTACT_KEYS];
    // Contacts of the pair this pass, counted once per pair of bodies.
    int pairContacts[CONTACT_KEYS];
    float shapes[];
};

// The push out of a wall or a block ends the touch within a pass, before
// the impulses of the block have reached the speed they aim for. While an
// impact lasts, a pair this much (in diameters) beyond touching keeps
// meeting, by speed alone. It stays within the neighbour cache's skin, and
// counting and solving agree on it.
const float SPECULATIVE_DIAMETERS = 0.4;

int contactKey(int group, int partner) {
    return min(group, partner) * CONTACT_PARTNERS + max(group, partner);
}

// A member of [group] meets wall [wall] (left, right, bottom, top) at
// [distance] from it.
bool meetsWall(int group, int wall, float distance) {
    float radius = 0.5 * uDiameterPixels;
    if (distance < radius) return true;
    // Far from the wall, the contact memory is not read at all.
    return distance < radius + SPECULATIVE_DIAMETERS * uDiameterPixels &&
        impacts[contactKey(group, MAXIMUM_GROUPS + wall)] != 0;
}

float contactReachSquared(uint first, uint second) {
    return contactReachSquared(materials[first], materials[second]);
}

float contactReachSquared(vec4 firstMaterial, vec4 secondMaterial) {
    bool firstMember = isMember(firstMaterial);
    bool secondMember = isMember(secondMaterial);
    // A grain broken off a block meets the liquid as a loose body does.
    bool fluidBlock = uLiquid != 0 &&
        (firstMember && secondMaterial.z == 0.0 || secondMember && firstMaterial.z == 0.0);
    float reach = fluidBlock ? FLUID_REACH_DIAMETERS * uDiameterPixels : uDiameterPixels;
    if (firstMember && secondMember &&
        impacts[contactKey(int(firstMaterial.w), int(secondMaterial.w))] != 0) {
        reach += SPECULATIVE_DIAMETERS * uDiameterPixels;
    }
    return reach * reach;
}

// Counts [contacts] contacts of block [group] with loose bodies, each a
// partner of its own (partnerShare).
void countLooseContacts(int group, uint contacts) {
    atomicAdd(groups[group].grid.w, int(contacts));
}

void countLooseContact(int group) {
    countLooseContacts(group, 1u);
}

// Counts a contact of block [group] with block [partner]. A block's partners
// are counted once each, by their first contact.
void countPartnerContact(int group, int partner) {
    if (atomicAdd(pairContacts[contactKey(group, partner)], 1) == 0) {
        atomicAdd(groups[group].grid.w, 1);
        atomicAdd(groups[partner].grid.w, 1);
    }
}

// Counts a contact of block [group] with wall [wall], a partner like a block.
void countWallContact(int group, int wall) {
    if (atomicAdd(pairContacts[contactKey(group, MAXIMUM_GROUPS + wall)], 1) == 0) {
        atomicAdd(groups[group].grid.w, 1);
    }
}

// Counts the walls a member of [group] meets.
void countWallContacts(int group, vec2 positionPixels) {
    if (uBounded == 0) return;
    if (meetsWall(group, 0, positionPixels.x)) countWallContact(group, 0);
    if (meetsWall(group, 1, uViewport.x - positionPixels.x)) countWallContact(group, 1);
    if (meetsWall(group, 2, positionPixels.y)) countWallContact(group, 2);
    if (meetsWall(group, 3, uViewport.y - positionPixels.y)) countWallContact(group, 3);
}

// GlueShape.FILL_DIAMETERS and RIPPLE_DIAMETERS.
const float FILL_DIAMETERS = 0.6;
const float RIPPLE_DIAMETERS = 0.1;

vec2 rigidOffset(vec2 delta) {
    // Walls: no minimum image.
    if (uBounded == 0) delta -= floor(delta / uViewport + vec2(0.5)) * uViewport;
    return delta;
}

vec2 rotate(vec2 rotation, vec2 v) {
    return vec2(rotation.x * v.x - rotation.y * v.y, rotation.y * v.x + rotation.x * v.y);
}

vec2 unrotate(vec2 rotation, vec2 v) {
    return vec2(rotation.x * v.x + rotation.y * v.y, -rotation.y * v.x + rotation.x * v.y);
}

// Drift of this pass, by which block poses are extrapolated.
float driftDt = 0.0;

// Pose of a block [dt] after its last projection: its members have drifted
// with its rigid motion since. At the drift of this pass it was found once
// for the pass (RigidGroup.pose), and at none it is the projection's own.
void groupPose(int group, float dt, out vec2 center, out vec2 rotation) {
    vec4 pose = dt == driftDt ? groups[group].pose : rigidPose(groups[group], dt);
    center = pose.xy;
    rotation = pose.zw;
}

// Bilinear distance field of a block in its own frame and its gradient.
float sampleShape(int group, vec2 local, out vec2 gradient) {
    vec4 shape = groups[group].shape;
    ivec4 grid = groups[group].grid;
    vec2 u = (local - shape.xy) / shape.z;
    vec2 last = vec2(grid.yz - 1);
    if (any(lessThan(u, vec2(0.0))) || any(greaterThanEqual(u, last))) {
        // Beyond the grid: its border value plus the distance to it.
        gradient = vec2(0.0);
        return length(max(max(-u, u - last), vec2(0.0))) * shape.z + shape.w;
    }
    ivec2 cell = ivec2(u);
    vec2 f = u - vec2(cell);
    int base = grid.x + cell.x + cell.y * grid.y;
    float a = shapes[base];
    float b = shapes[base + 1];
    float c = shapes[base + grid.y];
    float d = shapes[base + grid.y + 1];
    gradient = vec2(mix(b - a, d - c, f.y), mix(c - a, d - b, f.x)) / shape.z;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Value of the shape on the block's closed outline (GlueShape: the fill
// around the members, shifted out by its excess over a body radius). A body
// centre never belongs further in than this.
float outlineValue() {
    return (FILL_DIAMETERS - 0.5) * uDiameterPixels;
}

// Depth of a body in a block's shape and the normal pointing out of the
// block; a zero normal means apart. Down to -[slop], apart bodies still get
// their (negative) depth and normal. A body whose centre crossed the surface
// during this pass's drift [dt] is pushed back out where it came in, so a
// fast body cannot be pushed on through, however thin the block.
float shapeContact(int group, vec2 bodyPixels, vec2 bodyVelocity, float radius, float slop, float dt, out vec2 normal) {
    vec2 center;
    vec2 rotation;
    groupPose(group, dt, center, rotation);
    vec2 local = unrotate(rotation, rigidOffset(bodyPixels - center));
    vec2 gradient;
    float value = sampleShape(group, local, gradient);
    normal = vec2(0.0);
    if (value >= radius + slop) return 0.0;
    float penetration = radius - value;
    if (dt > 0.0) {
        vec2 startCenter;
        vec2 startRotation;
        groupPose(group, 0.0, startCenter, startRotation);
        vec2 start = unrotate(startRotation, rigidOffset(bodyPixels - bodyVelocity * dt - startCenter));
        vec2 startGradient;
        float travel = sampleShape(group, start, startGradient);
        float outline = outlineValue();
        if (travel > max(value, outline)) {
            // Came from further out: did the path cross the outline? Sphere
            // tracing; a bilinear field grows at most sqrt 2 times faster
            // than the distance.
            float surface = outline + 0.005 * uDiameterPixels;
            vec2 path = local - start;
            float pathLength = max(length(path), 1e-6);
            float t = 0.0;
            vec2 crossing = startGradient;
            // Between two ends above the surface the field dips at most to
            // the meeting point of their bounds: then no step can find it.
            bool apart = value > surface && travel + value - 1.5 * pathLength > 2.0 * surface;
            for (int step = 0; step < 24 && !apart && travel > surface && t < 1.0; step++) {
                t = min(t + (travel - outline) / (1.5 * pathLength), 1.0);
                travel = sampleShape(group, start + path * t, crossing);
            }
            if (travel <= surface) {
                // It entered at the crossing: back out there, however deep or
                // far through it is now.
                vec2 entry = start + path * t;
                vec2 outward = dot(crossing, crossing) > 1e-12 ? normalize(crossing) : normalize(start - local);
                normal = rotate(rotation, outward);
                return radius - outline + dot(entry - local, outward);
            }
        }
    }
    vec2 outward = dot(gradient, gradient) > 1e-12 ? normalize(gradient)
        : (dot(local, local) > 0.0 ? normalize(local) : vec2(0.0, 1.0));
    normal = rotate(rotation, outward);
    return penetration;
}

// Inverse mass of a block for a push along [normal] at [pointPixels], per
// unit member mass: 1/N + (r x n)^2 / sum |r0|^2.
float rigidResponse(int group, vec2 pointPixels, vec2 normal, float dt) {
    vec2 center;
    vec2 rotation;
    groupPose(group, dt, center, rotation);
    vec4 scalars = groups[group].scalars;
    float arm = cross2(rigidOffset(pointPixels - center), normal);
    return 1.0 / scalars.z + (scalars.y > 0.0 ? arm * arm / scalars.y : 0.0);
}

// Asks a block to move by [shift] after this pass (rigid_reduce.comp). The
// largest request per direction wins, so a contact seen from many member
// pairs moves it once.
void pushGroup(int group, vec2 shift) {
    ivec2 positive = ivec2(max(shift, vec2(0.0)) * GUARD_SCALE + 0.5);
    ivec2 negative = ivec2(max(-shift, vec2(0.0)) * GUARD_SCALE + 0.5);
    if (positive.x > 0) atomicMax(groups[group].guard.x, positive.x);
    if (positive.y > 0) atomicMax(groups[group].guard.y, positive.y);
    if (negative.x > 0) atomicMax(groups[group].guard.z, negative.x);
    if (negative.y > 0) atomicMax(groups[group].guard.w, negative.y);
}

// Like pushGroup, for a block resting on bodies a wall holds: the wall
// carries it, so the block also stops moving into them.
void supportGroup(int group, vec2 shift) {
    ivec2 positive = ivec2(max(shift, vec2(0.0)) * GUARD_SCALE + 0.5);
    ivec2 negative = ivec2(max(-shift, vec2(0.0)) * GUARD_SCALE + 0.5);
    if (positive.x > 0) atomicMax(groups[group].support.x, positive.x);
    if (positive.y > 0) atomicMax(groups[group].support.y, positive.y);
    if (negative.x > 0) atomicMax(groups[group].support.z, negative.x);
    if (negative.y > 0) atomicMax(groups[group].support.w, negative.y);
}

// Mass splitting: contacts solved side by side never push a block further
// than one contact solved alone would, as each meets a share of its mass.
// The block shares its mass equally among its partners, and with a partner
// among the pairs of bodies touching it. Shared among all pairs alike, a
// block a big one lies on would give the floor below a hundredth of the
// support it needs per pass, and the stack would sink and creep. This is the
// factor the block's inverse mass is multiplied by; momentum is kept all the same.
float partnerShare(int group, int partner) {
    float partners = float(max(groups[group].grid.w, 1));
    if (partner < 0) return partners;
    return partners * float(max(pairContacts[contactKey(group, partner)], 1));
}
