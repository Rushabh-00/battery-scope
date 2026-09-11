package com.batteryscope.app.battery

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeDeltaEstimatorTest {
    @Test
    fun acceptsReasonableRemainingChargeDelta() {
        val result = ChargeDeltaEstimator.estimate(
            remainingDeltaMah = 120.0,
            currentA = 3.0,
            deltaMs = 60_000L,
            charging = true,
        )

        assertEquals(120.0, result, 0.0)
    }

    @Test
    fun fallsBackToCurrentForFuelGaugeJump() {
        val result = ChargeDeltaEstimator.estimate(
            remainingDeltaMah = 2000.0,
            currentA = 3.0,
            deltaMs = 60_000L,
            charging = true,
        )

        assertEquals(50.0, result, 0.000001)
    }

    @Test
    fun acceptsNegativeRemainingDeltaWhenDischarging() {
        val result = ChargeDeltaEstimator.estimate(
            remainingDeltaMah = -80.0,
            currentA = -2.0,
            deltaMs = 60_000L,
            charging = false,
        )

        assertEquals(80.0, result, 0.0)
    }

    @Test
    fun usesCurrentIntegrationForTinyGaugeNoise() {
        val result = ChargeDeltaEstimator.estimate(
            remainingDeltaMah = 0.01,
            currentA = 1.8,
            deltaMs = 60_000L,
            charging = true,
        )

        assertEquals(30.0, result, 0.000001)
    }
}
