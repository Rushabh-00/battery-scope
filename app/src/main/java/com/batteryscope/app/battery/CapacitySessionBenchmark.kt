package com.batteryscope.app.battery

/** Marks a sufficiently deep charge as a stronger benchmark-quality capacity sample. */
val CapacitySessionTracker.FullChargeSession.benchmark: Boolean
    get() = startLevelPercent <= 10 && (100 - startLevelPercent) >= 80
