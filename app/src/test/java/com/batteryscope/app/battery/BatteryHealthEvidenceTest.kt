package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHealthEvidenceTest {
    @Test
    fun oneNormalSessionIsNotEnoughForHealth() {
        val session = CapacitySessionTracker.FullChargeSession(
            estimatedCapacityMah = 4700.0,
            chargedMah = 3995.0,
            durationMs = 3_600_000L,
            startedAtMs = 1L,
            completedAtMs = 3_600_001L,
            startLevelPercent = 15,
            qualityPercent = 95,
        )

        val result = BatteryHealthCalculator.calculateFromSessions(5000.0, listOf(session))
        assertNull(result.healthPercent)
        assertEquals(0, result.confidencePercent)
    }

    @Test
    fun deepChargeSessionCanProvideBenchmarkHealth() {
        val session = CapacitySessionTracker.FullChargeSession(
            estimatedCapacityMah = 4700.0,
            chargedMah = 4230.0,
            durationMs = 5_400_000L,
            startedAtMs = 1L,
            completedAtMs = 5_400_001L,
            startLevelPercent = 5,
            qualityPercent = 90,
        )

        val result = BatteryHealthCalculator.calculateFromSessions(5000.0, listOf(session))
        assertEquals(94.0, result.healthPercent!!, 0.0)
        assertEquals(88, result.confidencePercent)
    }

    @Test
    fun benchmarkClassificationRequiresDeepCharge() {
        val normal = CapacitySessionTracker.FullChargeSession(4700.0, 3995.0, 1L, 1L, 2L, 15, 90)
        val benchmark = CapacitySessionTracker.FullChargeSession(4700.0, 4230.0, 1L, 1L, 2L, 5, 90)

        assertEquals(false, normal.benchmark)
        assertEquals(true, benchmark.benchmark)
    }
}
