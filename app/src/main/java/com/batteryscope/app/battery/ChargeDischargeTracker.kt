package com.batteryscope.app.battery

/** Tracks charge and discharge amounts between app samples. */
class ChargeDischargeTracker {
    private var lastChargeCounterMah: Double? = null
    private var chargedMah = 0.0
    private var dischargedMah = 0.0

    fun update(remainingMah: Double?, charging: Boolean) {
        if (remainingMah == null) return
        val previous = lastChargeCounterMah
        lastChargeCounterMah = remainingMah
        if (previous == null) return

        val delta = remainingMah - previous
        if (delta > 0.0 && charging) {
            chargedMah += delta
        } else if (delta < 0.0 && !charging) {
            dischargedMah += -delta
        }
    }

    fun chargedMah(): Double = chargedMah
    fun dischargedMah(): Double = dischargedMah
}
