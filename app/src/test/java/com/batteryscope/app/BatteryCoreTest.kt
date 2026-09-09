package com.batteryscope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryCoreTest {
    @Test
    fun capacityIsScaledFromMeasuredCharge() {
        assertEquals(5000.0, calculateCapacityMah(2500.0, 50)!!, 0.001)
    }

    @Test
    fun capacityRejectsEmptyInput() {
        assertNull(calculateCapacityMah(0.0, 50))
        assertNull(calculateCapacityMah(1000.0, 0))
    }

    @Test
    fun wearUsesDesignCapacity() {
        assertEquals(1.0, calculateWearCycles(DESIGN_CAPACITY_MAH), 0.0001)
        assertEquals(0.5, calculateWearCycles(DESIGN_CAPACITY_MAH / 2.0), 0.0001)
    }

    @Test
    fun healthUsesRecentUsableSessions() {
        val sessions = listOf(
            ChargeSession(1, 2, 20, 85, 3250.0, 5000.0, 4.2, 0.65, 100.0),
            ChargeSession(3, 4, 10, 80, 3500.0, 5000.0, 4.2, 0.70, 100.0)
        )
        val health = estimateHealth(sessions)
        assertNotNull(health.capacityMah)
        assertEquals(5000.0, health.capacityMah!!, 0.001)
        assertEquals(100.0, health.healthPercent!!, 0.001)
        assertEquals(2, health.completedSessions)
        assertTrue(health.confidencePercent > 0)
    }

    @Test
    fun confidenceGrowsWithEvidence() {
        assertEquals(0, healthConfidence(0))
        assertEquals(20, healthConfidence(1))
        assertEquals(90, healthConfidence(8))
    }
}
