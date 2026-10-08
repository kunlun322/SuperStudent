package com.superstudent.core.network

import com.superstudent.core.model.SessionEventDto
import com.superstudent.core.model.ssJsonLenient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed interface SseSignal {
    data class Event(val dto: SessionEventDto) : SseSignal
    data class Failed(val t: Throwable) : SseSignal
    object Closed : SseSignal
}

/**
 * SSE watcher per design §1.4: reconnect is driven by the caller, which keeps
 * the last processed event id and passes it back as Last-Event-ID.
 */
class SseWatcher(private val client: OkHttpClient) {

    fun watch(request: Request): Flow<SseSignal> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val dto = runCatching { ssJsonLenient.decodeFromString<SessionEventDto>(data) }.getOrNull()
                if (dto != null) {
                    trySend(SseSignal.Event(dto))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(SseSignal.Failed(t ?: Exception("SSE failure ${response?.code}")))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(SseSignal.Closed)
                close()
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
}
