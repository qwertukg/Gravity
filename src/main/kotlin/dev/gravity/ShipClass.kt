package dev.gravity

import kotlin.math.atan2

/**
 * Classes of modern warship the naval battle's ships evolve through
 * (NavalCareer), from the patrol boat to the cruiser: each bigger, slower,
 * with more guns that reach further. Side views facing right, drawn as
 * polygons in px at a 1440 px tall screen from the stern at the keel (x to
 * the bow, y up): a hull with a raked bow, stealthy sloped superstructure,
 * mast and funnel, helicopter deck aft on the frigate and destroyer. Guns are
 * pivots on top of their mounts; all of a ship's guns fire at once, towards
 * the enemy.
 */
enum class ShipClass(
    val title: String,
    val length: Float,
    val height: Float,
    /** Fastest shot (px/s): the range. */
    val maximumShotSpeed: Float,
    val shellDiameter: Float,
    /** Speed of a shell's rim bodies when it goes off (px/s). */
    val blastSpeed: Float,
    val reloadSeconds: Float,
    /** Engine acceleration (px/s²) and the speed it stops at (px/s). */
    val thrust: Float,
    val maximumSpeed: Float,
    /** Experience the next class needs (NavalCareer). */
    val experienceToEvolve: Float,
    /** Gun pivots, x y pairs. */
    val guns: FloatArray,
    /** Polygons of x y pairs; the ship is their union. */
    val outline: List<FloatArray>,
) {
    PATROL_BOAT(
        "Patrol boat", 110f, 34f, maximumShotSpeed = 950f, shellDiameter = 10f, blastSpeed = 2000f, reloadSeconds = 0.6f,
        thrust = 420f, maximumSpeed = 280f, experienceToEvolve = 100f,
        guns = floatArrayOf(83f, 19f),
        outline = listOf(
            floatArrayOf(0f, 3f, 4f, 0f, 82f, 0f, 102f, 7f, 110f, 17f, 0f, 13f),
            floatArrayOf(38f, 12f, 70f, 12f, 66f, 24f, 42f, 24f),
            floatArrayOf(52f, 23f, 57f, 23f, 55f, 34f, 54f, 34f),
            floatArrayOf(78f, 14f, 88f, 14f, 87f, 19f, 79f, 19f),
        ),
    ),
    CORVETTE(
        "Corvette", 150f, 40f, maximumShotSpeed = 1050f, shellDiameter = 11f, blastSpeed = 2150f, reloadSeconds = 0.8f,
        thrust = 360f, maximumSpeed = 240f, experienceToEvolve = 150f,
        guns = floatArrayOf(114f, 22f, 22f, 21f),
        outline = listOf(
            floatArrayOf(0f, 4f, 5f, 0f, 115f, 0f, 138f, 8f, 150f, 20f, 0f, 16f),
            floatArrayOf(30f, 15f, 50f, 15f, 48f, 23f, 32f, 23f),
            floatArrayOf(50f, 15f, 100f, 15f, 95f, 30f, 56f, 30f),
            floatArrayOf(72f, 29f, 78f, 29f, 76f, 40f, 74f, 40f),
            floatArrayOf(108f, 17f, 120f, 17f, 119f, 22f, 109f, 22f),
            floatArrayOf(16f, 15f, 28f, 15f, 27f, 21f, 17f, 21f),
        ),
    ),
    FRIGATE(
        "Frigate", 190f, 48f, maximumShotSpeed = 1150f, shellDiameter = 12f, blastSpeed = 2300f, reloadSeconds = 1f,
        thrust = 310f, maximumSpeed = 210f, experienceToEvolve = 200f,
        guns = floatArrayOf(150f, 27f, 60f, 36f),
        outline = listOf(
            floatArrayOf(0f, 5f, 6f, 0f, 150f, 0f, 175f, 10f, 190f, 24f, 0f, 19f),
            // Hangar over the helicopter deck, the second gun on its roof.
            floatArrayOf(45f, 18f, 80f, 18f, 80f, 31f, 48f, 31f),
            floatArrayOf(54f, 30f, 66f, 30f, 65f, 36f, 55f, 36f),
            floatArrayOf(70f, 30f, 80f, 30f, 80f, 38f, 72f, 38f),
            floatArrayOf(80f, 18f, 130f, 18f, 124f, 36f, 86f, 36f),
            floatArrayOf(100f, 35f, 108f, 35f, 105f, 48f, 103f, 48f),
            floatArrayOf(143f, 21f, 157f, 21f, 156f, 27f, 144f, 27f),
        ),
    ),
    DESTROYER(
        "Destroyer", 230f, 54f, maximumShotSpeed = 1250f, shellDiameter = 13f, blastSpeed = 2450f, reloadSeconds = 1.2f,
        thrust = 270f, maximumSpeed = 180f, experienceToEvolve = 250f,
        guns = floatArrayOf(193f, 31f, 172f, 32f, 30f, 29f),
        outline = listOf(
            floatArrayOf(0f, 6f, 7f, 0f, 185f, 0f, 212f, 12f, 230f, 28f, 0f, 22f),
            floatArrayOf(40f, 21f, 85f, 21f, 85f, 34f, 44f, 34f),
            floatArrayOf(85f, 21f, 100f, 21f, 98f, 42f, 88f, 42f),
            floatArrayOf(100f, 21f, 165f, 21f, 158f, 42f, 116f, 42f),
            floatArrayOf(130f, 41f, 140f, 41f, 136f, 54f, 134f, 54f),
            // Vertical launch cells forward, the second gun raised on them.
            floatArrayOf(165f, 21f, 178f, 21f, 178f, 26f, 165f, 26f),
            floatArrayOf(166f, 25f, 178f, 25f, 177f, 32f, 167f, 32f),
            floatArrayOf(186f, 24f, 200f, 24f, 199f, 31f, 187f, 31f),
            floatArrayOf(24f, 21f, 36f, 21f, 35f, 29f, 25f, 29f),
        ),
    ),
    CRUISER(
        "Cruiser", 270f, 60f, maximumShotSpeed = 1350f, shellDiameter = 14f, blastSpeed = 2600f, reloadSeconds = 1.5f,
        thrust = 230f, maximumSpeed = 150f, experienceToEvolve = Float.POSITIVE_INFINITY,
        guns = floatArrayOf(223f, 37f, 200f, 40f, 32f, 33f, 50f, 38f),
        outline = listOf(
            floatArrayOf(0f, 7f, 8f, 0f, 220f, 0f, 250f, 14f, 270f, 32f, 0f, 26f),
            floatArrayOf(25f, 25f, 39f, 25f, 38f, 33f, 26f, 33f),
            floatArrayOf(44f, 25f, 56f, 25f, 55f, 38f, 45f, 38f),
            floatArrayOf(60f, 25f, 110f, 25f, 105f, 44f, 64f, 44f),
            floatArrayOf(110f, 25f, 126f, 25f, 124f, 48f, 112f, 48f),
            floatArrayOf(126f, 25f, 190f, 25f, 182f, 48f, 136f, 48f),
            floatArrayOf(152f, 47f, 162f, 47f, 158f, 60f, 156f, 60f),
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
     * Lowest elevation (rad) at which gun [gun]'s shell of radius [radius]
     * clears the ship ahead of it towards the bow: a gun aft fires over the
     * superstructure, as a real turret cannot be lowered into it.
     */
    fun minimumElevation(gun: Int, radius: Float): Float {
        val gunX = guns[2 * gun]
        val gunY = guns[2 * gun + 1]
        var lowest = 0f
        var x = gunX + 1f
        while (x < length) {
            var y = gunY
            while (y < height) {
                if (covers(x, y)) lowest = maxOf(lowest, atan2(y - gunY + radius + 1f, x - gunX))
                y += 1f
            }
            x += 1f
        }
        return lowest
    }

    private companion object {
        /** Even-odd ray casting. */
        fun inside(polygon: FloatArray, x: Float, y: Float): Boolean {
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
