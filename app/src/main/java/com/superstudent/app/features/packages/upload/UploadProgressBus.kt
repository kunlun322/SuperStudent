package com.superstudent.app.features.packages.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transient per-source upload progress for the notification and the row spinner. This is **not**
 * business state: Room is the only source of truth for whether an upload succeeded or failed, and
 * nothing here survives the process (design increment §2).
 */
object UploadProgressBus {

    data class Tick(val displayName: String, val sent: Long, val total: Long) {
        val percent: Int get() = if (total <= 0) 0 else ((sent * 100) / total).toInt().coerceIn(0, 100)
    }

    private val _ticks = MutableStateFlow<Map<String, Tick>>(emptyMap())
    val ticks: StateFlow<Map<String, Tick>> = _ticks.asStateFlow()

    fun begin(sourceId: String, displayName: String) {
        _ticks.value = _ticks.value + (sourceId to Tick(displayName, 0, 0))
    }

    fun progress(sourceId: String, sent: Long, total: Long) {
        val current = _ticks.value[sourceId] ?: return
        _ticks.value = _ticks.value + (sourceId to current.copy(sent = sent, total = total))
    }

    fun clear(sourceId: String) {
        if (sourceId !in _ticks.value) return
        _ticks.value = _ticks.value - sourceId
    }
}
