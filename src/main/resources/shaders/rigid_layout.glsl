// Glued blocks, matching RigidBlocks.kt. Members are listed per group and
// padded to whole 256-wide tiles, so every tile belongs to exactly one group;
// padding members are -1.
struct RigidGroup {
    vec4 frame;    // reference centre (px), centre (px)
    vec4 motion;   // velocity (px/s), rotation (cos, sin)
    vec4 scalars;  // angular velocity (rad/s), inertia sum |r0|^2, mass, unused
    ivec4 tiles;   // first tile, tile count, unused, unused
    vec4 shape;    // rest-frame origin of the shape grid (px), its spacing (px), smallest border value
    ivec4 grid;    // first shape value, grid width, grid height, contacts of the current pass
    vec4 bounds;   // rest-frame extent of the member centres: min x, max x, min y, max y (px)
    ivec4 guard;   // shift out of other blocks the pass asks for: +x, +y, -x, -y (1/GUARD_SCALE px)
};
// The groups buffer holds this many groups, then the shape values (GlueShape).
const int MAXIMUM_GROUPS = 256;
const float GUARD_SCALE = 65536.0;

float cross2(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}
