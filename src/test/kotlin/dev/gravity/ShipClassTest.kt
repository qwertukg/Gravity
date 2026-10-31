package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShipClassTest {
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
    /** A barrel's pivot above its mount and half its width, px at 1440 (NavalBattle). */
    private val LIFT = 2.7f
    private val HALF_WIDTH = 1.55f

    @Test
    fun `every ship is one block, its guns on it`() {
        for (type in ShipClass.entries) {
            for (facing in listOf(1f, -1f)) {
                val offsets = type.mask(facing).offsets(diameter, type.length, type.height)
                val pieces = BlockPieces.of(offsets, diameter)
                assertEquals(1, pieces.size, "${type.title} holds together")
            }
            for (gun in 0 until type.gunCount) {
                val x = type.guns[2 * gun]
                val y = type.guns[2 * gun + 1]
                assertTrue(type.covers(x, y - 1f), "${type.title} gun $gun stands on its mount")
                assertTrue(!type.covers(x, y + 1f), "${type.title} gun $gun is on top")
            }
        }
    }

    @Test
    fun `bigger classes have more guns and reach further`() {
        ShipClass.entries.zipWithNext { smaller, bigger ->
            assertTrue(bigger.length > smaller.length)
            assertTrue(bigger.gunCount >= smaller.gunCount)
            assertTrue(bigger.maximumShotSpeed > smaller.maximumShotSpeed)
        }
        assertEquals(1, ShipClass.PATROL_BOAT.gunCount)
        assertEquals(3, ShipClass.CRUISER.gunCount)
    }

    @Test
    fun `guns stand forward, three at most`() {
        for (type in ShipClass.entries) {
            assertTrue(type.gunCount in 1..3, "${type.title}: ${type.gunCount} guns")
            for (gun in 0 until type.gunCount) assertTrue(type.guns[2 * gun] > 0.5f * type.length, "${type.title} gun $gun forward")
            assertTrue(type.launcherX < 0.5f * type.length, "${type.title} launch cell aft")
        }
    }

    @Test
    fun `each gun has its own arc, clear of its ship`() {
        for (type in ShipClass.entries) {
            val arcs = (0 until type.gunCount).map { type.firingArc(it, true, LIFT, HALF_WIDTH, 0.5f * type.shellDiameter) }
            for ((gun, arc) in arcs.withIndex()) {
                val at45 = ((0.785f - ShipClass.MINIMUM_ELEVATION) / (ShipClass.MAXIMUM_ELEVATION - ShipClass.MINIMUM_ELEVATION) *
                    (ShipClass.ELEVATIONS - 1)).toInt()
                assertTrue(arc[at45], "${type.title} gun $gun fires at 45 deg")
                // Every allowed line of fire stays off the ship for a ship's length.
                for (step in arc.indices.filter { arc[it] }) {
                    val elevation = ShipClass.elevation(step)
                    val x = type.guns[2 * gun]
                    val y = type.guns[2 * gun + 1] + LIFT
                    var distance = type.barrelLength
                    while (distance < type.length) {
                        assertTrue(!type.covers(x + distance * kotlin.math.cos(elevation), y + distance * kotlin.math.sin(elevation)),
                            "${type.title} gun $gun at $elevation rad hits its ship $distance px out")
                        distance += 1f
                    }
                }
            }
            // A raised gun cannot fire as low as the one ahead of it, over which it fires.
            if (type.gunCount > 1) assertTrue(arcs[1].indexOfFirst { it } >= arcs[0].indexOfFirst { it }, type.title)
        }
    }

    @Test
    fun `experience evolves a ship class by class`() {
        val career = NavalCareer()
        assertEquals(ShipClass.PATROL_BOAT, career.shipClass)
        assertNull(career.gain(60f))
        assertEquals(ShipClass.CORVETTE, career.gain(60f))
        assertEquals(20f, career.experience)
        assertEquals(ShipClass.DESTROYER, career.gain(330f))
        career.gain(10_000f)
        assertEquals(ShipClass.CRUISER, career.shipClass)
    }
}
