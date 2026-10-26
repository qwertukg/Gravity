// Sleeping tiles, matching SleepTiles.kt. The screen is cut into tiles. A
// tile is active in a step when, in the step before, a body in it moved at
// least the resting displacement or had nothing holding it (no contact, no
// wall, for a block no partner). A tile is awake when it or one of its eight
// neighbours is active, and a block when any of its members stands in an
// awake tile. Every other body sleeps: no kick, no drift, no contacts, and
// the rigid projection leaves its block alone, so a resting pile costs
// nothing until something moving comes within a tile of it. The stamp of a
// step tags what is active and awake in it, so nothing is ever cleared.
// uSleep = 0 keeps every body awake: the first steps, and the step after
// any change of the scene.
//
// The first drift pass of a step tells every body's state once (bodyAwake,
// SLEEP_TILES); the other passes read that. A compute shader may use at
// most 16 storage blocks, so a pass binds only what it needs: SLEEP_TILES
// the tiles awake, SLEEP_MARK the tiles marked active for the next step.
uniform int uSleep;
uniform uint uSleepStamp;
uniform ivec2 uSleepTiles;
layout(std430, binding = 14) buffer BodyAwake { uint bodyAwake[]; };
layout(std430, binding = 15) buffer GroupAwake { uint groupAwake[]; };
#ifdef SLEEP_MARK
layout(std430, binding = 16) buffer TileActive { uint tileActive[]; };
#endif
#ifdef SLEEP_TILES
layout(std430, binding = 17) buffer TileAwake { uint tileAwake[]; };
#endif

int sleepTile(vec2 position) {
    ivec2 tile = clamp(ivec2(floor(position * vec2(uSleepTiles))), ivec2(0), uSleepTiles - 1);
    return tile.x + tile.y * uSleepTiles.x;
}

// Whether body [id] is awake this step, as the step's first drift told.
bool sleepBodyAwake(uint id) {
    return uSleep == 0 || bodyAwake[id] == uSleepStamp;
}

bool groupIsAwake(int group) {
    return uSleep == 0 || groupAwake[group] == uSleepStamp;
}

#ifdef SLEEP_TILES
bool tileIsAwake(vec2 position) {
    return uSleep == 0 || tileAwake[sleepTile(position)] == uSleepStamp;
}

// Whether a body of [material] at [position] (unit coordinates) is awake. A
// fluid body always is: the liquid passes move it every step.
bool sleepAwake(vec2 position, vec4 material, bool liquid) {
    if (uSleep == 0) return true;
    if (material.z != 0.0 && material.w >= 0.0) return groupIsAwake(int(material.w));
    if (liquid && material.z == 0.0) return true;
    return tileIsAwake(position);
}
#endif

#ifdef SLEEP_MARK
// Marks the tile of an active body for the next step.
void sleepMarkActive(vec2 position) {
    tileActive[sleepTile(position)] = uSleepStamp + 1u;
}
#endif
