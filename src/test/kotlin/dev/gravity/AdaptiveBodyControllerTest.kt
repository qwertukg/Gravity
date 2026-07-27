package dev.gravity

import kotlin.test.Test
import kotlin.test.assertTrue

class AdaptiveBodyControllerTest {
    @Test
    fun `sustained overload reduces active prefix`() {
        val controller = AdaptiveBodyController(1_000_000, 2_000_000, 13.5)
        repeat(45) { controller.record(40.0) }
        assertTrue(controller.activeBodies < 1_000_000)
    }

    @Test
    fun `sustained headroom grows active prefix`() {
        val controller = AdaptiveBodyController(100_000, 2_000_000, 13.5)
        repeat(45) { controller.record(3.0) }
        assertTrue(controller.activeBodies > 100_000)
    }
}
