package com.superstudent.core.model

import kotlin.math.roundToLong

/**
 * Credit amounts reach the app as per-event deltas and are summed, so binary floating point leaves
 * artefacts in the row — a student saw "已消耗 11.159999999999998 credits". Round when accumulating
 * and render at most two decimals; the platform itself reports hundredths.
 */
object Credits {

    fun round(value: Double): Double = (value * 100).roundToLong() / 100.0

    fun format(value: Double): String {
        val rounded = round(value)
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}
