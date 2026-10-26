package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals

class SubstepGovernorTest {
    private fun settle(governor: SubstepGovernor, gpuMillis: Double) =
        repeat(SubstepGovernor.SETTLE_FRAMES) { governor.update(gpuMillis) }

    @Test
    fun `a frame over budget runs proportionally fewer substeps`() {
        val governor = SubstepGovernor(16, 10.0)
        assertEquals(13, governor.update(12.0))
    }

    @Test
    fun `never fewer than half`() {
        val governor = SubstepGovernor(16, 10.0)
        assertEquals(8, governor.update(100.0))
        settle(governor, 100.0)
        assertEquals(8, governor.update(100.0))
    }

    @Test
    fun `waits for a change to show before the next`() {
        val governor = SubstepGovernor(16, 10.0)
        governor.update(12.0)
        repeat(SubstepGovernor.SETTLE_FRAMES) { assertEquals(13, governor.update(20.0)) }
        assertEquals(8, governor.update(20.0))
    }

    @Test
    fun `comes back one at a time well under budget`() {
        val governor = SubstepGovernor(16, 10.0)
        governor.update(30.0)
        settle(governor, 5.0)
        assertEquals(9, governor.update(5.0))
        settle(governor, 9.0)
        assertEquals(9, governor.update(9.0))
    }

    @Test
    fun `within budget nothing changes`() {
        val governor = SubstepGovernor(16, 10.0)
        assertEquals(16, governor.update(9.9))
        assertEquals(16, governor.update(Double.NaN))
    }

    @Test
    fun `no budget keeps every substep`() {
        assertEquals(16, SubstepGovernor(16, 0.0).update(50.0))
    }
}
