// Overcompression relief, in the RIGID variants of both collision solvers. A
// fast impact presses bodies onto a block, whose guard puts them back on its
// outline, and bodies a block holds move apart by position alone, each body's
// correction shared among all its contacts (1/N): a crowd of N bodies in one
// point spreads by about 1/sqrt(N) of a diameter a pass and never comes apart
// while the impact presses on, hundreds deep in one pixel and the bodies
// behind them as deep. Equal discs touch at most six others unless they
// overlap deeply, so a pair where either body has more than RELIEF_CROWD
// contacts and whose centres are closer than RELIEF_DEPTH_DIAMETERS is
// crushed together: it moves apart as if each body had six contacts, by
// position alone, at most RELIEF_LIMIT_DIAMETERS a pass. Bodies that are not
// crushed are untouched, and so is a small crowd that has come to rest (no
// faster than uRestingSpeed against each other): sand packed into a crater
// stays as still as it was. A crowd larger than RESTING_CROWD is relieved
// whatever its speed, or it would cost every pass for good. Without blocks
// pairs turn their push into speed and burst apart by themselves.
//
// Needs uDiameterPixels and uRestingSpeed (collision_rigid_contact.glsl).
const uint KISSING_NUMBER = 6u;
// Mildly packed sand in a crater keeps its count; only real crowds are relieved.
const uint RELIEF_CROWD = 24u;
const uint RESTING_CROWD = 48u;
const float RELIEF_DEPTH_DIAMETERS = 0.5;
const float RELIEF_LIMIT_DIAMETERS = 0.5;
vec2 overcompression = vec2(0.0);

// A pair's push beyond the shared one, if it is crushed; [crowd] is the larger
// contact count of the pair, [pairWeight] the share it already got, and
// [represented] the pairs of the crowd it stands for (collision_crowd.glsl).
void relieve(vec2 normal, float share, float penetration, float pairWeight, uint crowd, float represented,
    vec2 relativeVelocity) {
    if (crowd <= RELIEF_CROWD || penetration <= RELIEF_DEPTH_DIAMETERS * uDiameterPixels) return;
    if (crowd <= RESTING_CROWD && dot(relativeVelocity, relativeVelocity) <= uRestingSpeed * uRestingSpeed) return;
    overcompression += normal * (share * penetration * (represented / float(KISSING_NUMBER) - pairWeight));
}

// This body's move out of the crowd: never into a block it touches, along
// [outward] (zero for none), as the guard has just put it on the outline.
vec2 reliefMove(vec2 outward) {
    vec2 move = overcompression;
    if (dot(outward, outward) > 0.0) {
        vec2 away = normalize(outward);
        move -= min(dot(move, away), 0.0) * away;
    }
    float moveLength = length(move);
    float limit = RELIEF_LIMIT_DIAMETERS * uDiameterPixels;
    if (moveLength > limit) move *= limit / moveLength;
    return move;
}
