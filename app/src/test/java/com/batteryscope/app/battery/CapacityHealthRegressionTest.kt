package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapacityHealthRegressionTest {
    @Test
    fun ignoresSessionCapacityAboveDesignTolerance() {
        val session = CapacitySessionTracker.FullChargeSession(
            estimatedCapacityMah = 6634.0,
            chargedMah = 5638.9,
            durationMs = 4_800_000L,
            startedAtMs = 1L,
            completedAtMs = 4_800_001L,
            startLevelPercent = 15,
            qualityPercent = 98,
        )

        val result = BatteryHealthCalculator.calculateFromSessions(5000.0, listOf(session))
        assertNull(result.healthPercent)
        assertEquals(0, result.confidencePercent)
    }

    @Test
    fun rejectsFuelGaugeFullCapacityFarAboveDesign() {
        val result = BatteryHealthCalculator.calculateFromSources(5000.0, 6634.0, null, emptyList())
        assertNull(result.healthPercent)
        assertEquals(0, result.confidencePercent)
    }
}
