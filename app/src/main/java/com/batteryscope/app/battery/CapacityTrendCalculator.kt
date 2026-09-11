package com.batteryscope.app.battery

import kotlin.math.max

/** Summarizes capacity movement across recent completed sessions. */
object CapacityTrendCalculator {
    enum class Direction { IMPROVING, STABLE, DECLINING }

    data class Trend(
        val direction: Direction,
        val changeMah: Double,
        val changePercent: Double,
        val slopeMahPerSession: Double,
    )

    fun calculate(sessions: List<CapacitySessionTracker.FullChargeSession>): Trend? {
        val samples = sessions.filter { it.estimatedCapacityMah > 0.0 && it.estimatedCapacityMah.isFinite() }.takeLast(5)
        if (samples.size < 2) return null
        val first = samples.first().estimatedCapacityMah
        val last = samples.last().estimatedCapacityMah
        val changeMah = last - first
        val changePercent = if (first > 0.0) changeMah / first * 100.0 else 0.0
        val slope = changeMah / max(1, samples.lastIndex)
        val direction = when {
            changePercent <= -2.0 -> Direction.DECLINING
            changePercent >= 2.0 -> Direction.IMPROVING
            else -> Direction.STABLE
        }
        return Trend(direction, changeMah, changePercent, slope)
    }
}
