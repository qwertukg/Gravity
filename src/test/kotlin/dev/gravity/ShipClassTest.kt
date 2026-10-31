package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShipClassTest {
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS

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
        assertEquals(4, ShipClass.CRUISER.gunCount)
    }

    @Test
    fun `a gun aft fires over the superstructure`() {
        assertEquals(0f, ShipClass.DESTROYER.minimumElevation(0, 6f))
        assertTrue(ShipClass.DESTROYER.minimumElevation(2, 6f) > 0.1f)
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
