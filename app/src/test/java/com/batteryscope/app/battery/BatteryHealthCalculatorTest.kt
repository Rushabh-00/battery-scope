package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHealthCalculatorTest {
    @Test
    fun usesMedianInsteadOfSingleLowOutlier() {
        val result = BatteryHealthCalculator.calculate(5000.0, listOf(4500.0, 4480.0, 3200.0, 4520.0, 4510.0))

        assertEquals(90.0, result.healthPercent!!, 0.0)
        assertEquals(100, result.confidencePercent)
        assertEquals(500.0, result.wearMah!!, 0.0)
    }

    @Test
    fun usesMiddlePairForEvenSampleCount() {
        val result = BatteryHealthCalculator.calculate(5000.0, listOf(4400.0, 4500.0, 4600.0, 4700.0))

        assertEquals(91.0, result.healthPercent!!, 0.0)
        assertEquals(80, result.confidencePercent)
        assertEquals(450.0, result.wearMah!!, 0.0)
    }

    @Test
    fun confidenceBuildsWithIndependentSessions() {
        assertEquals(20, BatteryHealthCalculator.calculate(5000.0, listOf(4500.0)).confidencePercent)
        assertEquals(40, BatteryHealthCalculator.calculate(5000.0, listOf(4500.0, 4520.0)).confidencePercent)
        assertEquals(60, BatteryHealthCalculator.calculate(5000.0, listOf(4500.0, 4520.0, 4480.0)).confidencePercent)
        assertEquals(80, BatteryHealthCalculator.calculate(5000.0, listOf(4500.0, 4520.0, 4480.0, 4510.0)).confidencePercent)
    }

    @Test
    fun ignoresInvalidSamples() {
        val result = BatteryHealthCalculator.calculate(5000.0, listOf(0.0, -10.0, Double.NaN, 4500.0))

        assertEquals(90.0, result.healthPercent!!, 0.0)
        assertEquals(20, result.confidencePercent)
    }

    @Test
    fun returnsEmptyWhenThereAreNoValidSamples() {
        val result = BatteryHealthCalculator.calculate(5000.0, listOf(0.0, -1.0, Double.NaN))

        assertNull(result.healthPercent)
        assertEquals(0, result.confidencePercent)
        assertNull(result.wearMah)
    }
}
