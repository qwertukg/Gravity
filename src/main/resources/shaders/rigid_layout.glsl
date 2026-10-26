// Glued blocks, matching RigidBlocks.kt. Members are listed per group and
// padded to whole 256-wide tiles, so every tile belongs to exactly one group;
// padding members are -1.
struct RigidGroup {
    vec4 frame;    // reference centre (px), centre (px)
    vec4 motion;   // velocity (px/s), rotation (cos, sin)
    vec4 scalars;  // angular velocity (rad/s), inertia sum |r0|^2, mass, struck this pass (fracture)
    ivec4 tiles;   // first tile, tile count, first hull value in shapes, hull points
    vec4 shape;    // rest-frame origin of the shape grid (px), its spacing (px), smallest border value
    ivec4 grid;    // first shape value, grid width, grid height, partners in contact this pass
    vec4 bounds;   // rest-frame extent of the member centres: min x, max x, min y, max y (px)
    ivec4 guard;   // shift out of other blocks the pass asks for: +x, +y, -x, -y (1/GUARD_SCALE px)
    ivec4 support; // shift off bodies wedged against a wall, likewise
    vec4 pose;     // centre (px) and rotation after the drift of the current collision pass (rigidPose)
};
// The groups buffer holds this many groups, then the contact memory, then the
// shape values (GlueShape).
const int MAXIMUM_GROUPS = 256;
const float GUARD_SCALE = 65536.0;
// Contact memory per pair: a block's partners are the other blocks, then the
// four walls (left, right, bottom, top). A pair belongs to its lower block.
const int CONTACT_PARTNERS = MAXIMUM_GROUPS + 4;
const int CONTACT_KEYS = MAXIMUM_GROUPS * CONTACT_PARTNERS;
// Approach speeds are kept in 1/IMPACT_SCALE px/s.
const float IMPACT_SCALE = 16.0;
// How a pair met in a pass: touching, or still pushed towards its target speed.
const int CONTACT_TOUCHING = 1;
const int CONTACT_PUSHING = 2;
// Passes apart after which an impact is over: the pair flew apart.
const int IMPACT_IDLE_PASSES = 4;

// Fracture, matching RigidBlocks.kt. A shape's values are followed by its
// slot grid on the same nodes: the member slot whose rest centre lies in
// each cell, or -1 (a cell is half a diameter wide, so it holds at most one
// member of a packing), then its hull. Per member slot: vec4(strength factor,
// breaking: 0, or 1 + ejection speed (px/s); impact direction in the rest
// frame). Per group: uvec4(crack budget in 1/FRACTURE_BUDGET_SCALE members,
// nucleus (rank << 24 | slot), members chipped off, the passes it lost
// members in (low 16 bits) and of them those that may have split it (high
// 16 bits), counted on and never cleared, so the CPU sees what came after its
// last look whenever it looks).
const float FRACTURE_BUDGET_SCALE = 16.0;
const uint LOST_PASS = 1u;
const uint SPLIT_PASS = 1u << 16;
// A slot of a piece whose member broke off the parent after the CPU's look
// (rigid_adopt.comp PIECE): counted in the piece until rigid_fracture.comp
// lets it go, unlike an empty slot (-1).
const int MEMBER_GONE = -2;
// A list entry rigid_fracture.comp found let go already.
const uint ENTRY_SPENT = 0xffffffffu;
// Most members one contact record may pay for: keeps the sums in range.
const float MAXIMUM_RECORD_BUDGET = 4096.0;
const uint NUCLEUS_SLOT_MASK = 0xffffffu;
// Members to break off a pass are listed per block, in its own member slots
// (from tiles.x * 256): a block lists each of its slots at most once, so its
// list fits there, and a pass visits its own list alone. Members the CPU glued
// into a piece of their own follow, from MOVED_BASE on, the same way.
// (RigidBlocks.brokenList: the counts of both per block, then the lists.)
// A member broken off this pass, to be carved out of its block's shape
// (rigid_carve.comp): its rest centre, its block, and whether members are
// left next to it.
struct Carve {
    vec2 point;
    int group;
    int enclosed;
};

float cross2(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}

// Pose of a block [dt] after its last projection: its members have drifted
// with its rigid motion since. The collision passes take it at their drift
// from RigidGroup.pose, set once a pass for every block.
vec4 rigidPose(RigidGroup rigid, float dt) {
    float angle = rigid.scalars.x * dt;
    vec2 rotation = rigid.motion.zw;
    return vec4(rigid.frame.zw + rigid.motion.xy * dt,
        rotation.x * cos(angle) - rotation.y * sin(angle), rotation.y * cos(angle) + rotation.x * sin(angle));
}
