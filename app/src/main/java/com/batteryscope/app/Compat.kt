package com.batteryscope.app

/** Local aliases/helpers shared by Compose UI files. */
typealias RowScope = androidx.compose.foundation.layout.RowScope
typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

/** Convenience overload for formatting scalar Float values without widening at every call site. */
fun format2(value: Float): String = "%.2f".format(java.util.Locale.US, value.toDouble())
