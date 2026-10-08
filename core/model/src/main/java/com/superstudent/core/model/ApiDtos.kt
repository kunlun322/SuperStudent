package com.superstudent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- Identity ----------

@Serializable
data class IdentityDto(
    val id: String,
    @SerialName("external_id") val externalId: String? = null,
    val name: String? = null,
    val enabled: Boolean = true,
    val metadata: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class PagedIdentities(
    val data: List<IdentityDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("page_token") val pageToken: String? = null,
)

@Serializable
data class CreateIdentityRequest(
    @SerialName("external_id") val externalId: String,
    val name: String,
    val metadata: Map<String, String> = emptyMap(),
)

// ---------- Sessions ----------

@Serializable
data class SessionUsage(
    @SerialName("total_credits") val totalCredits: Double? = null,
)

/**
 * The two seconds counters sit directly under `stats`, not under `stats.usage` (ZLQ-84). `usage`
 * survives only as the single legacy path for `total_credits`; it is never a source of durations.
 */
@Serializable
data class SessionStats(
    @SerialName("active_seconds") val activeSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    val usage: SessionUsage? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val status: String? = null,
    val title: String? = null,
    val stats: SessionStats? = null,
    val usage: SessionUsage? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * The only usage shape allowed to reach Room (ZLQ-89 §4). Callers normalize a Session response into
 * this once and hand the same value to the terminal write, so credits and the two seconds counters
 * can never come from two different reads of the same session.
 *
 * Seconds are `Double` because the cloud sends both integer and fractional forms; a `Long` field
 * throws `JsonDecodingException` on `3828.19`. A non-finite or negative reading means "not
 * collected" and stays null — coercing it to 0 would be indistinguishable from a real zero-second
 * measurement, which is precisely how ZLQ-84 stayed hidden.
 */
data class SessionUsageSnapshot(
    val activeSeconds: Double?,
    val durationSeconds: Double?,
    val totalCredits: Double?,
) {
    companion object {
        /** No session, or a read that failed: every field stays uncollected. */
        val EMPTY = SessionUsageSnapshot(null, null, null)

        private fun collected(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0.0 }

        fun from(session: SessionDto?): SessionUsageSnapshot {
            if (session == null) return EMPTY
            val stats = session.stats
            return SessionUsageSnapshot(
                activeSeconds = collected(stats?.activeSeconds),
                durationSeconds = collected(stats?.durationSeconds),
                totalCredits = collected(session.usage?.totalCredits ?: stats?.usage?.totalCredits),
            )
        }
    }
}

@Serializable
data class CreateSessionRequest(
    @SerialName("identity_id") val identityId: String,
    @SerialName("template_id") val templateId: String,
    val title: String,
    val metadata: Map<String, String> = emptyMap(),
)

// ---------- Events ----------

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
)

@Serializable
data class StopReason(
    val type: String? = null,
)

@Serializable
data class ModelUsage(
    val credits: Double = 0.0,
)

@Serializable
data class QcaErrorBody(
    val code: String? = null,
    val message: String? = null,
    val type: String? = null,
)

/**
 * Lenient union of all Forward session event payloads the client consumes.
 * Unknown fields are ignored; unknown event types surface only [id]/[type].
 */
@Serializable
data class SessionEventDto(
    val id: String,
    val type: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("processed_at") val processedAt: String? = null,
    val content: List<ContentBlock>? = null,
    @SerialName("stop_reason") val stopReason: StopReason? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    @SerialName("model_usage") val modelUsage: ModelUsage? = null,
    val error: QcaErrorBody? = null,
    @SerialName("session_thread_id") val sessionThreadId: String? = null,
) {
    fun textContent(): String = content.orEmpty().filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
}

@Serializable
data class EventsResponse(
    val data: List<SessionEventDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SendEventsRequest(
    val events: List<UserMessageEvent>,
)

@Serializable
data class UserMessageEvent(
    val type: String = "user.message",
    val content: List<ContentBlock>,
)

// ---------- Drive ----------

@Serializable
data class DriveEntry(
    val path: String,
    val name: String? = null,
    val type: String,
    val size: Long = 0,
    val etag: String? = null,
    @SerialName("last_modified") val lastModified: String? = null,
)

@Serializable
data class DriveEntriesResponse(
    val entries: List<DriveEntry> = emptyList(),
    @SerialName("page_token") val pageToken: String? = null,
)

@Serializable
data class UploadUrlRequest(
    val path: String,
    @SerialName("content_type") val contentType: String,
)

@Serializable
data class DownloadUrlRequest(
    val path: String,
)

@Serializable
data class DeleteEntryRequest(
    val path: String,
    val recursive: Boolean = false,
)

@Serializable
data class PresignedUrlResponse(
    val url: String,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val etag: String? = null,
    val size: Long? = null,
)

@Serializable
data class DeleteEntryResponse(
    @SerialName("deleted_count") val deletedCount: Int = 0,
)

@Serializable
data class QcaErrorResponse(
    val error: QcaErrorBody? = null,
    @SerialName("request_id") val requestId: String? = null,
    val type: String? = null,
)
