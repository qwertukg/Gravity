package dev.gravity

/** Groups of the scenario menu. */
enum class TestGroup(val title: String) {
    SAND("Sand mode"),
    LIQUID("Liquid mode"),
    BLOCKS("Blocks"),
}

/**
 * The scenes breaking blocks was measured on (BlockBenchmark), one button
 * each in the scenario menu, by [group]: the bullet of the `+` key (50,000
 * bodies) into blocks and into a wall, cannonballs into blocks, a ram, a
 * block dropped into sand and sand shot into a block. Like the block
 * scenarios they run in the box of the screen's walls without gravity
 * between bodies. Sizes and speeds are authored for a 1440 px tall screen
 * and scale with it.
 */
enum class TestScenario(
    override val title: String,
    val group: TestGroup,
    override val field: Boolean = false,
    override val liquid: Boolean = false,
    override val unbreakable: Boolean = false,
    private val build: ScenarioBuilder.() -> Unit,
) : BlockScene {
    WALL_BULLET("50k bullet into a wall", TestGroup.SAND, build = { bulletIntoWall() }),

    LIQUID_WALL_BULLET("50k bullet into a wall", TestGroup.LIQUID, liquid = true, build = { bulletIntoWall() }),

    BULLET("50k bullet, 3000 px/s", TestGroup.BLOCKS, build = { bulletIntoBlock(300f, 150f, 3000f) }),

    SLOW_BULLET("50k bullet, 1500 px/s", TestGroup.BLOCKS, build = { bulletIntoBlock(300f, 150f, 1500f) }),

    BIG_BLOCK_BULLET("50k bullet, big block", TestGroup.BLOCKS, build = { bulletIntoBlock(500f, 250f, 3000f) }),

    SMALL_BLOCK_BULLET("50k bullet, small block", TestGroup.BLOCKS, build = { bulletIntoBlock(150f, 100f, 3000f) }),

    UNBREAKABLE_BULLET("50k bullet, unbreakable", TestGroup.BLOCKS, unbreakable = true,
        build = { bulletIntoBlock(300f, 150f, 3000f) }),

    SHOT("Cannonball, 4000 px/s", TestGroup.BLOCKS, build = {
        block(space.centerX - px(300f), space.centerY - px(150f), space.centerX + px(300f), space.centerY + px(150f), BLOCK_DENSITY)
        ball(px(100f), space.centerX - px(650f), space.centerY, px(4000f), 0f)
    }),

    BIG_SHOT("Cannonball, big block", TestGroup.BLOCKS, build = {
        block(space.centerX - px(450f), space.centerY - px(200f), space.centerX + px(450f), space.centerY + px(200f), BLOCK_DENSITY)
        ball(px(100f), space.centerX - px(800f), space.centerY + px(50f), px(6000f), 0f)
    }),

    RAMMING("Two blocks ram", TestGroup.BLOCKS, build = {
        block(space.centerX - px(350f), space.centerY - px(160f), space.centerX - px(30f), space.centerY + px(160f), BLOCK_DENSITY,
            velocityX = px(1500f))
        block(space.centerX + px(30f), space.centerY - px(100f), space.centerX + px(350f), space.centerY + px(220f), BLOCK_DENSITY,
            velocityX = -px(1500f))
    }),

    SAND_DROP("Block into sand", TestGroup.BLOCKS, field = true, build = {
        sandBed(px(60f))
        block(space.centerX - px(200f), px(200f), space.centerX + px(200f), px(300f), BLOCK_DENSITY, velocityY = -px(2500f))
    }),

    CRATER("Sand into a block", TestGroup.BLOCKS, field = true, build = {
        block(space.centerX - px(100f), 0f, space.centerX + px(100f), px(80f), BLOCK_DENSITY)
        ball(px(30f), space.centerX, px(300f), 0f, -px(1500f))
    });

    override val optionName: String get() = name.lowercase().replace('_', '-')

    override fun setup(space: ScenarioSpace, seed: Long): BlockSetup = blockSetup(title, space, seed, build)

    private companion object {
        const val BLOCK_DENSITY = 0.5f
        /** Bodies of the bullet of the `+` key (ProjectileConfig.plusKeyBodyCount) at 1440 px. */
        const val BULLET_BODIES = 50_000

        fun ScenarioBuilder.px(pixelsAt1440: Float) = space.scaled(pixelsAt1440)

        fun ScenarioBuilder.bulletDiameter() =
            px(CircularProjectile.diameterForBodyCount(space.contactDiameterPixels, BULLET_BODIES))

        /** A block [halfWidth] by [halfHeight] from the centre, and the bullet just short of its left face. */
        fun ScenarioBuilder.bulletIntoBlock(halfWidth: Float, halfHeight: Float, speed: Float) {
            block(space.centerX - px(halfWidth), space.centerY - px(halfHeight),
                space.centerX + px(halfWidth), space.centerY + px(halfHeight), BLOCK_DENSITY)
            val diameter = bulletDiameter()
            ball(diameter, space.centerX - px(halfWidth + 4f) - 0.5f * diameter, space.centerY + px(0.15f * halfHeight), px(speed), 0f)
        }

        /** The bullet just short of the right wall, at 3000 px/s into it. */
        fun ScenarioBuilder.bulletIntoWall() {
            val diameter = bulletDiameter()
            ball(diameter, space.widthPixels - px(4f) - 0.5f * diameter, space.centerY, px(3000f), 0f)
        }

        /** Bodies at rest along the whole floor, [depth] px deep, a little apart so they settle without a spray. */
        fun ScenarioBuilder.sandBed(depth: Float) {
            val diameter = space.contactDiameterPixels
            val step = 1.02f * diameter
            val rowStep = step * kotlin.math.sqrt(3f) * 0.5f
            val perRow = (space.widthPixels / step).toInt()
            val rows = minOf((depth / rowStep).toInt() + 1, Scenario.MAXIMUM_BODIES / maxOf(perRow, 1))
            for (row in 0 until rows) {
                val y = 0.5f * diameter + row * rowStep
                var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * step
                while (x <= space.widthPixels - 0.5f * diameter) {
                    add(x, y)
                    x += step
                }
            }
        }
    }
}
