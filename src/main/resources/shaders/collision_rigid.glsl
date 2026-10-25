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
    float shapes[];
};

// Wall contacts a body has, for counting the contacts of its block.
uint wallTouches(vec2 positionPixels) {
    if (uBounded == 0) return 0u;
    float radius = 0.5 * uDiameterPixels;
    return uint(positionPixels.x < radius) + uint(uViewport.x - positionPixels.x < radius) +
        uint(positionPixels.y < radius) + uint(uViewport.y - positionPixels.y < radius);
}

// GlueShape.FILL_DIAMETERS.
const float FILL_DIAMETERS = 0.6;

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

// Pose of a block [dt] after its last projection: its members have drifted
// with its rigid motion since.
void groupPose(int group, float dt, out vec2 center, out vec2 rotation) {
    RigidGroup rigid = groups[group];
    float angle = rigid.scalars.x * dt;
    center = rigid.frame.zw + rigid.motion.xy * dt;
    rotation = rotate(rigid.motion.zw, vec2(cos(angle), sin(angle)));
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

// Depth of a body in a block's shape (zero or less: apart) and the normal
// pointing out of the block. A body whose centre crossed the surface during
// this pass's drift [dt] is pushed back out where it came in, so a fast body
// cannot be pushed on through, however thin the block.
float shapeContact(int group, vec2 bodyPixels, vec2 bodyVelocity, float radius, float dt, out vec2 normal) {
    vec2 center;
    vec2 rotation;
    groupPose(group, dt, center, rotation);
    vec2 local = unrotate(rotation, rigidOffset(bodyPixels - center));
    vec2 gradient;
    float value = sampleShape(group, local, gradient);
    normal = vec2(0.0);
    if (value >= radius) return 0.0;
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
            for (int step = 0; step < 24 && travel > surface && t < 1.0; step++) {
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

// Asks a block to move by [shift] after this pass (rigid_finalize.comp). The
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

// Mass splitting: a block in n contacts this pass meets each with 1/n of
// its mass, so contacts solved side by side never push it further than one
// contact solved alone would. Momentum is kept all the same.
float groupContacts(int group) {
    return float(max(groups[group].grid.w, 1));
}
