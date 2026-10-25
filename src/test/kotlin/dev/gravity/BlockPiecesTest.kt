package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockPiecesTest {
    private val diameter = 1.2f
    private val block = RectangularBlock.offsets(diameter, 30f, 20f)

    @Test
    fun `a whole block is one piece`() {
        assertEquals(listOf(block.size), BlockPieces.of(block, diameter).map { it.size })
    }

    @Test
    fun `a row broken out cuts a hexagonal block in two`() {
        val row = block.map { it.y }.distinct().sorted()[5]
        val cut = block.filter { it.y != row }
        val pieces = BlockPieces.of(cut, diameter)
        assertEquals(2, pieces.size)
        assertEquals(cut.size, pieces.sumOf { it.size })
        // Largest first: five rows below the cut, the rest above it.
        assertEquals(cut.count { it.y > row }, pieces[0].size)
    }

    @Test
    fun `a zigzag crack of single members cuts a block too`() {
        // From the bottom row to the top, always to a neighbour above.
        val rows = block.map { it.y }.distinct().sorted()
        var x = block.filter { it.y == rows[0] }.minOf { it.x } + 8 * diameter
        val crack = HashSet<PixelOffset>()
        for ((index, y) in rows.withIndex()) {
            val member = block.filter { it.y == y }.minBy { kotlin.math.abs(it.x - x) }
            crack += member
            x = member.x + if (index % 3 == 0) 0.5f * diameter else -0.5f * diameter
        }
        assertEquals(2, BlockPieces.of(block.filter { it !in crack }, diameter).size)
    }

    /** What [separated][BlockPieces.separated] finds once the members [gone] of [block] broke off. */
    private fun separated(gone: (PixelOffset) -> Boolean): List<IntArray> {
        val bonds = BlockPieces.bonds(FloatArray(block.size) { block[it].x }, FloatArray(block.size) { block[it].y }, diameter)
        val alive = BooleanArray(block.size) { !gone(block[it]) }
        val seeds = block.indices.filter { !alive[it] }
            .flatMap { member -> (0 until BlockPieces.BONDS).map { bonds[member * BlockPieces.BONDS + it] } }
            .filter { it >= 0 && alive[it] }.distinct().toIntArray()
        return BlockPieces.separated(bonds, alive, seeds)
    }

    @Test
    fun `only the smaller side of a cut is walked and comes away`() {
        val row = block.map { it.y }.distinct().sorted()[3]
        val pieces = separated { it.y == row }
        assertEquals(1, pieces.size)
        assertEquals(block.count { it.y < row }, pieces[0].size)
    }

    @Test
    fun `a crack that does not cut through takes nothing away`() {
        val rows = block.map { it.y }.distinct().sorted()
        val column = block.minOf { it.x } + 10 * diameter
        val pieces = separated { kotlin.math.abs(it.x - column) < 0.3f * diameter && it.y < rows[rows.size - 3] }
        assertEquals(0, pieces.size)
    }

    @Test
    fun `a corner chipped off comes away`() {
        val minX = block.minOf { it.x }
        val minY = block.minOf { it.y }
        // A diagonal cut off the lower left corner.
        val cut = { it: PixelOffset -> (it.x - minX) + (it.y - minY) in (4 * diameter)..(5.2f * diameter) }
        val corner = block.count { !cut(it) && (it.x - minX) + (it.y - minY) < 4 * diameter }
        val pieces = separated(cut)
        assertEquals(1, pieces.size)
        assertEquals(corner, pieces[0].size)
    }

    @Test
    fun `a gap of one missing member keeps the block whole`() {
        assertEquals(1, BlockPieces.of(block.drop(1).filterIndexed { index, _ -> index != 40 }, diameter).size)
    }
}
