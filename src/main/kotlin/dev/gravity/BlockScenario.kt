package dev.gravity

import kotlin.math.sqrt
import kotlin.random.Random

/** Loose bodies (normalised, as Scenario.bodies) and the blocks to glue after them (px). */
class BlockSetup(val bodies: FloatArray, val blocks: List<ScenarioBlock>)

/**
 * A start that brings its own environment: the box of the screen's walls,
 * no gravity between bodies, the downward field where [field] says so,
 * liquid mode where [liquid] says so, and the blocks to glue.
 */
sealed interface BlockScene : MenuScenario {
    val field: Boolean
    val liquid: Boolean
    /** Blocks never break here, whatever --block-strength says. */
    val unbreakable: Boolean get() = false

    fun setup(space: ScenarioSpace, seed: Long): BlockSetup
}

/** Builds a block scene: its loose bodies normalised and its blocks. */
internal fun blockSetup(title: String, space: ScenarioSpace, seed: Long, build: ScenarioBuilder.() -> Unit): BlockSetup {
    val builder = ScenarioBuilder(space, Random(seed))
    builder.build()
    // A body parked in a top corner keeps even a scene of blocks alone
    // from starting without bodies.
    if (builder.count == 0) builder.add(space.contactDiameterPixels, space.heightPixels - space.contactDiameterPixels)
    require(builder.count <= Scenario.MAXIMUM_BODIES) {
        "$title has ${builder.count} bodies; the limit is ${Scenario.MAXIMUM_BODIES}"
    }
    // Unlike the cosmic scenarios, the net momentum stays: it is the shot.
    return BlockSetup(builder.normalized(), builder.blocks.toList())
}

/**
 * Experiments with glued blocks: the scenes their behaviour was checked on
 * (BlockPenetrationTest, BlockDynamicsTest), started with --scenario; the
 * menu offers the test scenarios instead (TestScenario). They all run in the
 * box of the screen's walls without gravity between bodies, with the
 * downward field where [field] says so and in liquid mode where [liquid]
 * says so. Sizes and speeds are authored for a 1440 px tall screen and scale
 * with it, like the field.
 */
enum class BlockScenario(
    override val title: String,
    override val field: Boolean,
    override val liquid: Boolean,
    private val build: ScenarioBuilder.() -> Unit,
) : BlockScene {
    BULLETS("Bullets into blocks", field = false, liquid = false, {
        // 3000 and 6000 px/s into 20 px blocks, and 3000 px/s into a 3 px plate.
        for ((lane, shot) in listOf(
            Triple(0.75f, 3000f, 20f),
            Triple(0.5f, 6000f, 20f),
            Triple(0.25f, 3000f, 3f),
        ).withIndex()) {
            val (height, speed, thickness) = shot
            val y = space.heightPixels * height
            block(space.centerX, y - px(100f), space.centerX + px(thickness), y + px(100f), BLOCK_DENSITY)
            ball(px(if (lane == 2) 20f else 30f), space.centerX - px(400f), y, px(speed), 0f)
        }
    }),

    CANNONBALL("Cannonball into a block", field = false, liquid = false, {
        block(space.centerX, space.centerY - px(100f), space.centerX + px(40f), space.centerY + px(100f), BLOCK_DENSITY)
        ball(px(100f), space.centerX - px(600f), space.centerY, px(4000f), 0f)
    }),

    CRUSH("Cannonball crushes blocks", field = false, liquid = false, {
        val right = space.widthPixels
        block(right - px(42f), space.centerY - px(100f), right - px(22f), space.centerY + px(100f), BLOCK_DENSITY)
        block(right - px(212f), space.centerY - px(70f), right - px(192f), space.centerY + px(70f), BLOCK_DENSITY)
        ball(px(100f), right - px(700f), space.centerY, px(4000f), 0f)
    }),

    STACK("Block dropped onto a block", field = true, liquid = false, {
        block(space.centerX - px(150f), 0f, space.centerX + px(150f), px(30f), BLOCK_DENSITY)
        block(space.centerX - px(50f), px(150f), space.centerX + px(50f), px(190f), BLOCK_DENSITY)
    }),

    BIG_ON_SMALL("Big block on a small one", field = true, liquid = false, {
        // Laid on its middle it rests; laid off the edge it tips off and leans.
        block(space.centerX - px(420f), 0f, space.centerX - px(380f), px(30f), BLOCK_DENSITY)
        block(space.centerX - px(500f), px(31f), space.centerX - px(300f), px(71f), BLOCK_DENSITY)
        block(space.centerX + px(380f), 0f, space.centerX + px(420f), px(30f), BLOCK_DENSITY)
        block(space.centerX + px(344f), px(31f), space.centerX + px(504f), px(61f), BLOCK_DENSITY)
    }),

    RAM("Block rams a block", field = false, liquid = false, {
        block(space.centerX + px(100f), space.centerY - px(50f), space.centerX + px(140f), space.centerY + px(50f), BLOCK_DENSITY)
        block(space.centerX - px(300f), space.centerY - px(20f), space.centerX - px(250f), space.centerY + px(20f), BLOCK_DENSITY, velocityX = px(1500f))
    }),

    PILE("Heavy block into a pile", field = true, liquid = false, {
        // A loose granular layer, a little apart so it settles without a spray.
        lattice(depth = px(60f), spacing = 1.3f) { _, _ -> true }
        block(space.centerX - px(50f), px(300f), space.centerX + px(50f), px(340f), HEAVY_DENSITY)
    }),

    LIQUID("Blocks sink and rise in liquid", field = true, liquid = true, {
        // A light block under the surface rises, a heavy one sinks.
        val light = floatArrayOf(space.centerX + px(200f), px(8f), space.centerX + px(300f), px(28f))
        val margin = space.contactDiameterPixels
        lattice(depth = px(45f), spacing = 1f) { x, y ->
            x < light[0] - margin || x > light[2] + margin || y < light[1] - margin || y > light[3] + margin
        }
        block(light[0], light[1], light[2], light[3], LIGHT_DENSITY)
        block(space.centerX - px(300f), px(90f), space.centerX - px(220f), px(130f), HEAVY_DENSITY)
    }),

    BOUNCE("Blocks bounce off the floor", field = true, liquid = false, {
        block(space.centerX - px(250f), px(100f), space.centerX - px(150f), px(140f), BLOCK_DENSITY)
        block(space.centerX + px(150f), px(300f), space.centerX + px(250f), px(340f), BLOCK_DENSITY)
    }),

    WALL("Block thrown into a wall", field = false, liquid = false, {
        val right = space.widthPixels
        block(right - px(300f), space.centerY - px(30f), right - px(240f), space.centerY + px(30f), BLOCK_DENSITY, velocityX = px(600f))
    }),

    MASSES("Small and big blocks collide", field = false, liquid = false, {
        // Above: a small block into a big one; below: a big one into a small one.
        val upper = space.heightPixels * 0.7f
        val lower = space.heightPixels * 0.3f
        block(space.centerX - px(310f), upper - px(10f), space.centerX - px(290f), upper + px(10f), BLOCK_DENSITY, velocityX = px(400f))
        block(space.centerX + px(60f), upper - px(40f), space.centerX + px(140f), upper + px(40f), BLOCK_DENSITY)
        block(space.centerX - px(340f), lower - px(40f), space.centerX - px(260f), lower + px(40f), BLOCK_DENSITY, velocityX = px(400f))
        block(space.centerX + px(90f), lower - px(10f), space.centerX + px(110f), lower + px(10f), BLOCK_DENSITY)
    }),

    SLIDING("Blocks slide with friction", field = true, liquid = false, {
        // On the floor, and on a long block.
        block(space.centerX - px(1000f), 0f, space.centerX - px(900f), px(40f), BLOCK_DENSITY, velocityX = px(300f))
        block(space.centerX + px(100f), 0f, space.centerX + px(570f), px(30f), BLOCK_DENSITY)
        block(space.centerX + px(130f), px(31f), space.centerX + px(230f), px(71f), BLOCK_DENSITY, velocityX = px(300f))
    }),

    BODIES("Bodies bounce off blocks", field = false, liquid = false, {
        // Single bodies into a small and a big block: both bounce alike, the
        // big block takes their momentum with less speed.
        val upper = space.heightPixels * 0.7f
        val lower = space.heightPixels * 0.3f
        block(space.centerX - px(15f), upper - px(15f), space.centerX + px(15f), upper + px(15f), BLOCK_DENSITY)
        block(space.centerX - px(60f), lower - px(60f), space.centerX + px(60f), lower + px(60f), BLOCK_DENSITY)
        add(space.centerX - px(300f), upper, px(300f), 0f)
        add(space.centerX - px(300f), lower, px(300f), 0f)
    }),

    SLOW_BODY("Slow body against a block", field = true, liquid = false, {
        block(space.centerX, 0f, space.centerX + px(100f), px(40f), BLOCK_DENSITY)
        add(space.centerX - px(50f), 0.6f * space.contactDiameterPixels, px(20f), 0f)
    }),

    THROWN("Blocks thrown and spun", field = true, liquid = false, {
        // At an angle, and spinning.
        block(space.centerX - px(550f), px(120f), space.centerX - px(450f), px(150f), BLOCK_DENSITY,
            velocityX = px(300f), velocityY = -px(200f))
        block(space.centerX + px(250f), px(120f), space.centerX + px(350f), px(150f), BLOCK_DENSITY,
            velocityY = -px(100f), spin = 3f)
    }),

    DROPS("Blocks on liquid drops", field = true, liquid = true, {
        // A dozen drops on the floor under a heavy and under a light block.
        for ((side, density) in listOf(-1f to HEAVY_DENSITY, 1f to BLOCK_DENSITY)) {
            val left = space.centerX + side * px(250f) - px(50f)
            for (k in 0 until 12) add(left + px(30f) + k * px(4f), 0.6f * space.contactDiameterPixels)
            block(left, px(40f), left + px(100f), px(70f), density)
        }
    });

    override val optionName: String get() = name.lowercase().replace('_', '-')

    override fun setup(space: ScenarioSpace, seed: Long): BlockSetup = blockSetup(title, space, seed, build)

    private companion object {
        const val BLOCK_DENSITY = 0.5f
        const val HEAVY_DENSITY = 3f
        const val LIGHT_DENSITY = 0.3f
        /** Bodies of a pool or a pile at most, whatever the screen. */
        const val LAYER_BODIES = 150_000

        fun ScenarioBuilder.px(pixelsAt1440: Float) = space.scaled(pixelsAt1440)

        /**
         * Bodies at rest on a hexagonal lattice along the whole floor, [depth] px
         * deep (or shallower to stay within LAYER_BODIES), [spacing] diameters
         * apart, where [keep] allows.
         */
        fun ScenarioBuilder.lattice(depth: Float, spacing: Float, keep: (Float, Float) -> Boolean) {
            val step = spacing * space.contactDiameterPixels
            val rowStep = step * sqrt(3f) * 0.5f
            val perRow = (space.widthPixels / step).toInt()
            val rows = minOf((depth / rowStep).toInt() + 1, LAYER_BODIES / maxOf(perRow, 1))
            for (row in 0 until rows) {
                val y = 0.5f * space.contactDiameterPixels + row * rowStep
                var x = 0.5f * space.contactDiameterPixels + if (row % 2 == 0) 0f else 0.5f * step
                while (x <= space.widthPixels - 0.5f * space.contactDiameterPixels) {
                    if (keep(x, y)) add(x, y)
                    x += step
                }
            }
        }
    }
}
