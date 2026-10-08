package com.superstudent.app.features.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Start rejections and start failures the service could not record in Room, because they never
 * produced a run row — the D-2 admission guard turning away a second concurrent first build is the
 * main one.
 *
 * A `StateFlow` rather than a shared one because the service answers a tap asynchronously: the value
 * has to survive a collector that is briefly gone, which is what a rotation or a back-navigation in
 * the window between the tap and the answer does. Like
 * [com.superstudent.app.features.packages.upload.UploadProgressBus] this is transient UI state only,
 * and Room remains the source of truth for every run that exists.
 *
 * Two rules keep that stickiness from outliving the condition it reports:
 * - the rejection carries the package it belongs to, so a detail screen never shows a guard decision
 *   that turned away a different package;
 * - the screen that matches consumes it (reads it, then [clear]s), so it is delivered once rather
 *   than replayed into every later visit to that package.
 */
object TaskStartBus {

    data class Rejection(val packageId: String, val message: String)

    private val _message = MutableStateFlow<Rejection?>(null)
    val message: StateFlow<Rejection?> = _message.asStateFlow()

    fun post(packageId: String, message: String) {
        _message.value = Rejection(packageId, message)
    }

    fun clear() {
        _message.value = null
    }
}
