// Needs uViewport and uBounded.
float pairDistanceSquared(uint id, vec2 position, uint other, vec2 otherPosition) {
    // The reference counter evaluates an unordered pair in ascending ID order
    // and increments BOTH counts. Preserve that orientation here as well:
    // reversing an expression that the driver contracts into an FMA can change
    // its last bit at the strict contact threshold. The own position comes from
    // a register instead of a reload; the operands are the same floats.
    bool ownFirst = id < other;
    vec2 firstPixels = (ownFirst ? position : otherPosition) * uViewport;
    precise vec2 delta = firstPixels - (ownFirst ? otherPosition : position) * uViewport;
    // Walls: no minimum image, bodies across an edge never touch.
    if (uBounded == 0) delta -= floor(delta / uViewport + vec2(0.5)) * uViewport;
    precise float distanceSquared = dot(delta, delta);
    return distanceSquared;
}

#ifdef RIGID
// Crushed crowds, in the RIGID cache passes. A fast impact presses hundreds
// of bodies into one point against a block (collision_relief.glsl), and every
// body of such a crowd meets every other one: the solve's work grows with the
// square of the crowd. A body with more than CROWDED_CONTACTS loose contacts
// solves a random sample of them instead, SAMPLED_CONTACTS on average. A
// loose pair is solved when its key lies below the sampling threshold of both
// its bodies, so it stays equal and opposite, and its weight shares the body
// among the sample; its relief stands for the pairs left out. The sum over a
// sample estimates the sum over all, so a crowd comes apart as a whole, not
// from its surface alone, as it would keeping only the nearest. A loose body
// solves all its contacts with blocks; a member of a block a crowd presses on
// samples its side of them and scales it by the pairs it stands for
// (memberSample). A body with fewer contacts solves all, as before: below
// that a crowd is not relieved (RELIEF_CROWD) and nothing changes.
uniform uint CROWDED_CONTACTS;
uniform uint SAMPLED_CONTACTS;
// A body solving all its contacts (cache plane KEEP_PLANE).
const uint KEEP_ALL = 0xffffffffu;

// The threshold below which a body with [loose] loose contacts samples
// SAMPLED_CONTACTS of them; it never rises with more.
uint sampleThreshold(uint loose) {
    return loose <= SAMPLED_CONTACTS ? KEEP_ALL : uint(4294967296.0 * float(SAMPLED_CONTACTS) / float(loose));
}

// The same for both bodies of a pair, uniform over all values.
uint pairKey(uint first, uint second) {
    uint hash = min(first, second) * 0x9e3779b9u ^ max(first, second) * 0x85ebca6bu;
    hash ^= hash >> 16;
    hash *= 0x7feb352du;
    hash ^= hash >> 15;
    hash *= 0x846ca68bu;
    hash ^= hash >> 16;
    return hash;
}
#endif
