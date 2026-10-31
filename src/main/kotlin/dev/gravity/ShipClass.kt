package dev.gravity

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Classes of modern warship the naval battle's ships evolve through
 * (NavalCareer), from the patrol boat to the cruiser: each bigger, slower,
 * with more guns that reach further. Side views facing right, drawn as
 * polygons in px at a 1440 px tall screen from the stern at the keel (x to
 * the bow, y up): a hull with a raked bow, stealthy sloped superstructure,
 * mast and funnel, helicopter deck aft on the frigate and destroyer. Guns,
 * one to three, stand forward: pivots on top of their mounts, where a barrel
 * turns (NavalBattle), each raised over the one ahead of it. Aft stands the
 * launch cell of the missile (NavalMissile).
 */
enum class ShipClass(
    val title: String,
    val length: Float,
    val height: Float,
    /** Shot speed (px/s): low enough that shells fly in arcs; the range. */
    val maximumShotSpeed: Float,
    val shellDiameter: Float,
    val barrelLength: Float,
    /** Speed of a shell's rim bodies when it goes off (px/s). */
    val blastSpeed: Float,
    val reloadSeconds: Float,
    /** Engine acceleration (px/s²) and the speed it stops at (px/s). */
    val thrust: Float,
    val maximumSpeed: Float,
    /** Experience the next class needs (NavalCareer). */
    val experienceToEvolve: Float,
    /** Gun pivots, x y pairs, from the bow aft. */
    val guns: FloatArray,
    /** Top of the missile's launch cell. */
    val launcherX: Float,
    val launcherY: Float,
    /** Polygons of x y pairs; the ship is their union. */
    val outline: List<FloatArray>,
) {
    PATROL_BOAT(
        "Patrol boat", 110f, 34f, maximumShotSpeed = 820f, shellDiameter = 6f, barrelLength = 12f, blastSpeed = 1800f, reloadSeconds = 0.6f,
        thrust = 420f, maximumSpeed = 280f, experienceToEvolve = 100f,
        guns = floatArrayOf(83f, 19f),
        launcherX = 14f, launcherY = 17f,
        outline = listOf(
            floatArrayOf(0f, 3f, 4f, 0f, 82f, 0f, 102f, 7f, 110f, 17f, 0f, 13f),
            floatArrayOf(38f, 12f, 70f, 12f, 66f, 24f, 42f, 24f),
            floatArrayOf(52f, 23f, 57f, 23f, 55f, 34f, 54f, 34f),
            floatArrayOf(78f, 14f, 88f, 14f, 87f, 19f, 79f, 19f),
            floatArrayOf(8f, 11f, 20f, 11f, 20f, 17f, 8f, 17f),
        ),
    ),
    CORVETTE(
        "Corvette", 150f, 40f, maximumShotSpeed = 860f, shellDiameter = 6.5f, barrelLength = 14f, blastSpeed = 1950f, reloadSeconds = 0.8f,
        thrust = 360f, maximumSpeed = 240f, experienceToEvolve = 150f,
        guns = floatArrayOf(114f, 22f),
        launcherX = 22f, launcherY = 21f,
        outline = listOf(
            floatArrayOf(0f, 4f, 5f, 0f, 115f, 0f, 138f, 8f, 150f, 20f, 0f, 16f),
            floatArrayOf(30f, 15f, 50f, 15f, 48f, 23f, 32f, 23f),
            floatArrayOf(50f, 15f, 100f, 15f, 95f, 30f, 56f, 30f),
            floatArrayOf(72f, 29f, 78f, 29f, 76f, 40f, 74f, 40f),
            floatArrayOf(108f, 17f, 120f, 17f, 119f, 22f, 109f, 22f),
            floatArrayOf(16f, 15f, 28f, 15f, 28f, 21f, 16f, 21f),
        ),
    ),
    FRIGATE(
        "Frigate", 190f, 48f, maximumShotSpeed = 900f, shellDiameter = 7f, barrelLength = 16f, blastSpeed = 2100f, reloadSeconds = 1f,
        thrust = 310f, maximumSpeed = 210f, experienceToEvolve = 200f,
        guns = floatArrayOf(150f, 27f, 134f, 33f),
        launcherX = 60f, launcherY = 36f,
        outline = listOf(
            floatArrayOf(0f, 5f, 6f, 0f, 150f, 0f, 175f, 10f, 190f, 24f, 0f, 19f),
            // Hangar over the helicopter deck, the launch cell on its roof.
            floatArrayOf(45f, 18f, 80f, 18f, 80f, 31f, 48f, 31f),
            floatArrayOf(54f, 30f, 66f, 30f, 66f, 36f, 54f, 36f),
            floatArrayOf(70f, 30f, 80f, 30f, 80f, 38f, 72f, 38f),
            floatArrayOf(80f, 18f, 130f, 18f, 124f, 36f, 86f, 36f),
            floatArrayOf(100f, 35f, 108f, 35f, 105f, 48f, 103f, 48f),
            floatArrayOf(128f, 18f, 140f, 18f, 139f, 33f, 129f, 33f),
            floatArrayOf(143f, 21f, 157f, 21f, 156f, 27f, 144f, 27f),
        ),
    ),
    DESTROYER(
        "Destroyer", 230f, 54f, maximumShotSpeed = 940f, shellDiameter = 7.5f, barrelLength = 18f, blastSpeed = 2250f, reloadSeconds = 1.2f,
        thrust = 270f, maximumSpeed = 180f, experienceToEvolve = 250f,
        guns = floatArrayOf(193f, 31f, 172f, 36f),
        launcherX = 30f, launcherY = 29f,
        outline = listOf(
            floatArrayOf(0f, 6f, 7f, 0f, 185f, 0f, 212f, 12f, 230f, 28f, 0f, 22f),
            floatArrayOf(40f, 21f, 85f, 21f, 85f, 34f, 44f, 34f),
            floatArrayOf(85f, 21f, 100f, 21f, 98f, 42f, 88f, 42f),
            floatArrayOf(100f, 21f, 165f, 21f, 158f, 42f, 116f, 42f),
            floatArrayOf(130f, 41f, 140f, 41f, 136f, 54f, 134f, 54f),
            // Vertical launch cells forward, the second gun raised on them.
            floatArrayOf(165f, 21f, 178f, 21f, 178f, 26f, 165f, 26f),
            floatArrayOf(166f, 25f, 178f, 25f, 177f, 36f, 167f, 36f),
            floatArrayOf(186f, 24f, 200f, 24f, 199f, 31f, 187f, 31f),
            floatArrayOf(24f, 21f, 36f, 21f, 36f, 29f, 24f, 29f),
        ),
    ),
    CRUISER(
        "Cruiser", 270f, 60f, maximumShotSpeed = 980f, shellDiameter = 8f, barrelLength = 20f, blastSpeed = 2400f, reloadSeconds = 1.5f,
        thrust = 230f, maximumSpeed = 150f, experienceToEvolve = Float.POSITIVE_INFINITY,
        guns = floatArrayOf(223f, 37f, 200f, 40f, 179f, 52f),
        launcherX = 32f, launcherY = 33f,
        outline = listOf(
            floatArrayOf(0f, 7f, 8f, 0f, 220f, 0f, 250f, 14f, 270f, 32f, 0f, 26f),
            floatArrayOf(25f, 25f, 39f, 25f, 39f, 33f, 25f, 33f),
            floatArrayOf(60f, 25f, 110f, 25f, 105f, 44f, 64f, 44f),
            floatArrayOf(110f, 25f, 126f, 25f, 124f, 48f, 112f, 48f),
            floatArrayOf(126f, 25f, 190f, 25f, 182f, 48f, 136f, 48f),
            floatArrayOf(152f, 47f, 162f, 47f, 158f, 60f, 156f, 60f),
            // The third gun raised on the front of the superstructure.
            floatArrayOf(172f, 44f, 186f, 44f, 185f, 52f, 173f, 52f),
            floatArrayOf(192f, 29f, 208f, 29f, 207f, 40f, 193f, 40f),
            floatArrayOf(215f, 29f, 231f, 29f, 230f, 37f, 216f, 37f),
        ),
    );

    val gunCount get() = guns.size / 2

    /** The class a ship evolves into, or null for the last. */
    val next get() = entries.getOrNull(ordinal + 1)

    /** Whether point ([x], [y]) of the side view (from the stern at the keel) is solid. */
    fun covers(x: Float, y: Float) = outline.any { inside(it, x, y) }

    /** The ship as a block outline facing right ([facing] 1) or left, one picture pixel per px at 1440. */
    fun mask(facing: Float): BlockMask {
        val width = length.toInt()
        val rows = height.toInt()
        val solid = BooleanArray(width * rows) { index ->
            val column = index % width
            val x = column + 0.5f
            covers(if (facing > 0f) x else length - x, rows - index / width - 0.5f)
        }
        return BlockMask("${name.lowercase()}-${if (facing > 0f) "right" else "left"}", width, rows, solid)
    }

    /**
     * The elevations ([ELEVATIONS] steps from [MINIMUM_ELEVATION] to
     * [MAXIMUM_ELEVATION]) at which gun [gun], its pivot [lift] above its
     * mount, can aim towards the bow ([forward]) or the stern without hitting
     * its own ship: its barrel ([barrelHalfWidth] wide each side) clears the
     * ship, and so does its shell ([shellRadius]) along its line of fire, past
     * the barrels of the guns ahead, which lie parallel to it. In px at 1440.
     */
    fun firingArc(gun: Int, forward: Boolean, lift: Float, barrelHalfWidth: Float, shellRadius: Float): BooleanArray {
        val pivotX = guns[2 * gun]
        val pivotY = guns[2 * gun + 1] + lift
        val side = if (forward) 1f else -1f
        return BooleanArray(ELEVATIONS) { step ->
            val elevation = elevation(step)
            val directionX = side * cos(elevation)
            val directionY = sin(elevation)
            clear(pivotX, pivotY, directionX, directionY, barrelHalfWidth, shellRadius) &&
                (0 until gunCount).filter { it != gun }.none { other ->
                    // A barrel ahead on a parallel line too close.
                    val dx = guns[2 * other] - pivotX
                    val dy = guns[2 * other + 1] + lift - pivotY
                    val along = dx * directionX + dy * directionY
                    val across = abs(dx * directionY - dy * directionX)
                    along > 0f && across < 2f * barrelHalfWidth + shellRadius + CLEARANCE
                }
        }
    }

    /** Whether the line from ([x], [y]) along the direction clears the ship: the barrel's length thin, then a shell's width. */
    private fun clear(x: Float, y: Float, directionX: Float, directionY: Float, barrelHalfWidth: Float, shellRadius: Float): Boolean {
        var distance = 0f
        while (distance < SIGHT_LENGTHS * length) {
            val reach = if (distance < barrelLength) barrelHalfWidth + BARREL_CLEARANCE else shellRadius + CLEARANCE
            val pointX = x + directionX * distance
            val pointY = y + directionY * distance
            for (offset in floatArrayOf(-reach, -0.5f * reach, 0f, 0.5f * reach, reach)) {
                if (covers(pointX - directionY * offset, pointY + directionX * offset)) return false
            }
            distance += 0.5f
        }
        return true
    }

    companion object {
        /** Elevations of [firingArc], rad. */
        const val ELEVATIONS = 181
        const val MINIMUM_ELEVATION = -0.3f
        const val MAXIMUM_ELEVATION = 1.5f
        /** Room kept between a line of fire and the ship, px at 1440: as much as the firing cut-out wants (two bodies). */
        const val CLEARANCE = 2.5f
        /** Room kept between a barrel and the ship, px at 1440: it turns just over its own mount. */
        const val BARREL_CLEARANCE = 1f
        /** How far, in ship lengths, a line of fire is followed. */
        const val SIGHT_LENGTHS = 1.5f

        fun elevation(step: Int) = MINIMUM_ELEVATION + step * (MAXIMUM_ELEVATION - MINIMUM_ELEVATION) / (ELEVATIONS - 1)

        /** Even-odd ray casting. */
        private fun inside(polygon: FloatArray, x: Float, y: Float): Boolean {
            var inside = false
            val points = polygon.size / 2
            var previous = points - 1
            for (point in 0 until points) {
                val x1 = polygon[2 * point]
                val y1 = polygon[2 * point + 1]
                val x2 = polygon[2 * previous]
                val y2 = polygon[2 * previous + 1]
                if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) inside = !inside
                previous = point
            }
            return inside
        }
    }
}

/**
 * One player's progress over the battles of a session: their ship's class
 * and the experience towards the next. Experience comes from damage done to
 * the enemy ship (100 for all of it) and [WIN_BONUS] for a win; enough of it
 * evolves the ship into the next class for the next battle.
 */
class NavalCareer {
    var shipClass = ShipClass.entries.first()
        private set
    var experience = 0f
        private set

    /** Adds [points]; returns the class the ship evolved into, if it did. */
    fun gain(points: Float): ShipClass? {
        experience += points
        var evolved: ShipClass? = null
        while (experience >= shipClass.experienceToEvolve) {
            val next = shipClass.next ?: break
            experience -= shipClass.experienceToEvolve
            shipClass = next
            evolved = next
        }
        return evolved
    }

    companion object {
        const val WIN_BONUS = 50f
    }
}
