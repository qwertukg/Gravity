// Glued blocks, matching RigidBlocks.kt. Members are listed per group and
// padded to whole 256-wide tiles, so every tile belongs to exactly one group;
// padding members are -1.
struct RigidGroup {
    vec4 frame;    // reference centre (px), centre (px)
    vec4 motion;   // velocity (px/s), rotation (cos, sin)
    vec4 scalars;  // angular velocity (rad/s), inertia sum |r0|^2, mass, unused
    ivec4 tiles;   // first tile, tile count, unused, unused
};

float cross2(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}
