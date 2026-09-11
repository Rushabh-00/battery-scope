package com.batteryscope.app.battery

import org.junit.Assert.assertTrue
import org.junit.Test

class CapacitySessionQualityTest {
    @Test
    fun strongSessionScoresHigherThanShortNoisySession() {
        val strong = CapacitySessionQuality.calculate(5, 100, 4300.0, 180 * 60_000L, 4300.0, 4200.0)
        val weak = CapacitySessionQuality.calculate(15, 40, 900.0, 8 * 60_000L, 900.0, 1500.0)

        assertTrue(strong > weak)
        assertTrue(strong >= 80)
    }
}
