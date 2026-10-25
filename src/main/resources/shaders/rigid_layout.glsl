// Glued blocks, matching RigidBlocks.kt. Members are listed per group and
// padded to whole 256-wide tiles, so every tile belongs to exactly one group;
// padding members are -1.
struct RigidGroup {
    vec4 frame;    // reference centre (px), centre (px)
    vec4 motion;   // velocity (px/s), rotation (cos, sin)
    vec4 scalars;  // angular velocity (rad/s), inertia sum |r0|^2, mass, unused
    ivec4 tiles;   // first tile, tile count, first hull value in shapes, hull points
    vec4 shape;    // rest-frame origin of the shape grid (px), its spacing (px), smallest border value
    ivec4 grid;    // first shape value, grid width, grid height, partners in contact this pass
    vec4 bounds;   // rest-frame extent of the member centres: min x, max x, min y, max y (px)
    ivec4 guard;   // shift out of other blocks the pass asks for: +x, +y, -x, -y (1/GUARD_SCALE px)
    ivec4 support; // shift off bodies wedged against a wall, likewise
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

float cross2(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}
