package com.batteryscope.app.battery

/** Validates a low-to-full charge span before turning it into a capacity measurement. */
object CapacitySessionEstimator {
    private const val MIN_LEVEL_SPAN_PERCENT = 60
    private const val MIN_CHARGED_MAH = 100.0
    private const val MIN_CAPACITY_MAH = 100.0
    private const val MAX_CAPACITY_MAH = 30_000.0

    fun estimate(
        activeChargeMah: Double,
        startLevelPercent: Int,
        endLevelPercent: Int,
        minimumLevelSpanPercent: Int = MIN_LEVEL_SPAN_PERCENT,
    ): Double? {
        if (!activeChargeMah.isFinite() || activeChargeMah < MIN_CHARGED_MAH) return null
        if (startLevelPercent !in 0..15 || endLevelPercent !in 1..100) return null

        val levelSpan = endLevelPercent - startLevelPercent
        if (levelSpan < minimumLevelSpanPercent) return null

        return (activeChargeMah * 100.0 / levelSpan)
            .takeIf { it in MIN_CAPACITY_MAH..MAX_CAPACITY_MAH }
    }
}
