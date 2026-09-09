package com.batteryscope.app.battery

import android.content.Context

/** Stores an optional user-supplied design/reference capacity in mAh. */
class CapacityPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var designCapacityMah: Double?
        get() = preferences.getString(KEY_DESIGN_CAPACITY, null)
            ?.toDoubleOrNull()
            ?.takeIf { it in 100.0..30_000.0 }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_DESIGN_CAPACITY)
                else putString(KEY_DESIGN_CAPACITY, value.toString())
            }.apply()
        }

    private companion object {
        const val FILE_NAME = "battery_scope_capacity"
        const val KEY_DESIGN_CAPACITY = "design_capacity_mah"
    }
}
