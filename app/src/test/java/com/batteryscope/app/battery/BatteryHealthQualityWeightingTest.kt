package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryHealthQualityWeightingTest {
    @Test
    fun lowQualityOutlierDoesNotDriveWeightedMedian() {
        val sessions = listOf(
            session(4400.0, 90),
            session(4450.0, 95),
            session(3000.0, 20),
            session(4420.0, 90),
        )

        val result = BatteryHealthCalculator.calculate(5000.0, sessions)

        assertEquals(89.0, result.healthPercent!!, 0.0)
        assertEquals(72, result.confidencePercent)
    }

    private fun session(capacityMah: Double, qualityPercent: Int) = CapacitySessionTracker.FullChargeSession(
        estimatedCapacityMah = capacityMah,
        chargedMah = capacityMah,
        durationMs = 3_600_000L,
        startedAtMs = 1L,
        completedAtMs = 2L,
        startLevelPercent = 10,
        qualityPercent = qualityPercent,
    )
}
