package dev.gravity

import kotlin.test.Test
import kotlin.test.assertEquals

class GpuProfileSummaryTest {
    @Test
    fun `summary aggregates repeated stages and ignores missing optional stages`() {
        val first = DoubleArray(GpuStage.entries.size) { Double.NaN }
        val second = DoubleArray(GpuStage.entries.size) { Double.NaN }
        first[GpuStage.COLLISION_SOLVE.ordinal] = 4.0
        second[GpuStage.COLLISION_SOLVE.ordinal] = 2.0
        first[GpuStage.FRAME.ordinal] = 10.0
        second[GpuStage.FRAME.ordinal] = 8.0

        val summary = GpuProfileSummary.from(
            listOf(
                GpuFrameProfile(10, first),
                GpuFrameProfile(11, second),
            )
        )

        assertEquals(2, summary.frames)
        assertEquals(3.0, summary[GpuStage.COLLISION_SOLVE]!!.averageMillis)
        assertEquals(2.0, summary[GpuStage.COLLISION_SOLVE]!!.medianMillis)
        assertEquals(4.0, summary[GpuStage.COLLISION_SOLVE]!!.p95Millis)
        assertEquals(null, summary[GpuStage.TRAJECTORY])
    }
}
