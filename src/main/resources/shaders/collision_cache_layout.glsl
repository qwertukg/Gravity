// Structure of arrays, one lane per body. These are cache capacities, never
// contact limits: overflowing bodies traverse the complete reference grid.
const uint NEIGHBOR_SLOTS = 32u;
const uint CONTACT_SLOTS = 8u;
const uint CACHE_PLANES = 2u + NEIGHBOR_SLOTS + CONTACT_SLOTS;
const uint DIRECT_GRID = 0xffffffffu;
uniform int uCapacity;

uint cacheOffset(uint plane, uint id) {
    return plane * uint(uCapacity) + id;
}

// Control words follow the per-body planes, so every pass can reach them
// within OpenGL 4.3's eight storage bindings.
const uint STATE_REBUILD = 0u;        // this pass rebuilds the broad phase
const uint STATE_POPULATION = 1u;     // bodies in the last build; 0 forces a rebuild
const uint STATE_REBUILDS = 2u;       // rebuild counter (diagnostics)
const uint STATE_DIRECT = 3u;         // this pass uses the ordinary contact grid
const uint STATE_DENSE_SAMPLES = 4u;  // sampled dense bodies of the current count
const uint STATE_GRID_EPOCH = 5u;     // tag of the retained linked grid

uint stateOffset(uint word) {
    return CACHE_PLANES * uint(uCapacity) + word;
}

// Grid node of the last build: its position, the next body in its cell, and
// in RIGID builds its block + 1 (blockTag), so a pass skips the members of
// its own block without reading their materials.
struct GridNode {
    vec2 position;
    uint next;
    uint tag;
};

// Heads hold (epoch << uIdBits) | (id + 1). A head written by an older build is
// an empty list, so the grid never needs a clearing pass.
uniform int uIdBits;

uint headBody(uint head, uint epoch) {
    return (head >> uint(uIdBits)) == epoch ? head & ((1u << uint(uIdBits)) - 1u) : 0u;
}

// RIGID builds also tag each grid cell with its epoch << CELL_TAG_BITS and
// the block tag its bodies share, or CELL_MIXED: a member whose stencil holds
// only cells of its own block (or empty ones) has no hard pair to look for.
const uint CELL_TAG_BITS = 9u;
const uint CELL_MIXED = 511u;

// Liquid mode: a fluid body only makes hard pairs with solid bodies. Every
// build tags the tiles of SOLID_TILE x SOLID_TILE cells that hold a solid body
// with its epoch, so a fluid body skips a stencil without one. A stale or
// colliding tag only costs a needless walk.
const int SOLID_TILE = 8;
uniform int uSolidTileWidth;

int solidTile(ivec2 cell) {
    return cell.x / SOLID_TILE + cell.y / SOLID_TILE * uSolidTileWidth;
}
