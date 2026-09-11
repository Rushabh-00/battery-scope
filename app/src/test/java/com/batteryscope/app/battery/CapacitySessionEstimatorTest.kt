package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapacitySessionEstimatorTest {
    @Test
    fun acceptsSufficientLowToFullSpan() {
        assertEquals(4500.0, CapacitySessionEstimator.estimate(3600.0, 20 - 5, 95), 0.0)
    }

    @Test
    fun rejectsSmallLevelSpan() {
        assertNull(CapacitySessionEstimator.estimate(500.0, 15, 34))
    }

    @Test
    fun rejectsSmallChargedAmount() {
        assertNull(CapacitySessionEstimator.estimate(99.9, 10, 90))
    }

    @Test
    fun rejectsOutOfRangeCapacity() {
        assertNull(CapacitySessionEstimator.estimate(10000.0, 15, 90))
    }
}
