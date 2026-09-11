package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CapacityTrendCalculatorTest {
    @Test
    fun detectsDecliningTrend() {
        val sessions = listOf(
            session(4500.0),
            session(4400.0),
            session(4300.0),
        )

        val trend = CapacityTrendCalculator.calculate(sessions)

        assertNotNull(trend)
        assertEquals(CapacityTrendCalculator.Direction.DECLINING, trend!!.direction)
        assertEquals(-200.0, trend.changeMah, 0.0)
    }

    private fun session(capacityMah: Double) = CapacitySessionTracker.FullChargeSession(
        estimatedCapacityMah = capacityMah,
        chargedMah = capacityMah,
        durationMs = 3_600_000L,
        startedAtMs = 1L,
        completedAtMs = 2L,
        startLevelPercent = 10,
    )
}
