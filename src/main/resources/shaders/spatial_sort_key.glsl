// Tile key of the spatial reordering. Tiles follow a Morton (Z-order) curve,
// so bodies that are close on screen get close IDs and share cache lines in
// every neighbor search. The key only orders bodies; it never filters them.
uniform int uSortWidthBits;
uniform int uSortHeightBits;

uint spreadBits(uint value) {
    value &= 0xffffu;
    value = (value | (value << 8)) & 0x00ff00ffu;
    value = (value | (value << 4)) & 0x0f0f0f0fu;
    value = (value | (value << 2)) & 0x33333333u;
    value = (value | (value << 1)) & 0x55555555u;
    return value;
}

uint sortKey(vec2 position) {
    ivec2 size = ivec2(1 << uSortWidthBits, 1 << uSortHeightBits);
    // The clamp also gives non-finite positions some valid tile.
    uvec2 tile = uvec2(clamp(ivec2(floor(position * vec2(size))), ivec2(0), size - 1));
    int commonBits = min(uSortWidthBits, uSortHeightBits);
    uint low = (1u << commonBits) - 1u;
    uint morton = spreadBits(tile.x & low) | (spreadBits(tile.y & low) << 1);
    // The longer axis keeps its remaining bits on top; the shorter has none.
    return morton | (((tile.x | tile.y) >> commonBits) << (2 * commonBits));
}
