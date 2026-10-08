package com.superstudent.core.network

import com.superstudent.core.model.CreateIdentityRequest
import com.superstudent.core.model.CreateSessionRequest
import com.superstudent.core.model.DeleteEntryRequest
import com.superstudent.core.model.DeleteEntryResponse
import com.superstudent.core.model.DownloadUrlRequest
import com.superstudent.core.model.DriveEntriesResponse
import com.superstudent.core.model.EventsResponse
import com.superstudent.core.model.IdentityDto
import com.superstudent.core.model.PagedIdentities
import com.superstudent.core.model.PresignedUrlResponse
import com.superstudent.core.model.SendEventsRequest
import com.superstudent.core.model.SessionDto
import com.superstudent.core.model.UploadUrlRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface QcaApi {

    @GET("api/v1/forward/identities")
    suspend fun listIdentities(
        @Query("external_id") externalId: String,
        @Query("limit") limit: Int = 2,
    ): PagedIdentities

    @POST("api/v1/forward/identities")
    suspend fun createIdentity(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateIdentityRequest,
    ): IdentityDto

    @GET("api/v1/forward/identities/{identity_id}")
    suspend fun getIdentity(@Path("identity_id") identityId: String): IdentityDto

    @POST("api/v1/forward/sessions")
    suspend fun createSession(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateSessionRequest,
    ): SessionDto

    @GET("api/v1/forward/sessions/{session_id}")
    suspend fun getSession(@Path("session_id") sessionId: String): SessionDto

    @POST("api/v1/forward/sessions/{session_id}/events")
    suspend fun sendEvents(
        @Path("session_id") sessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: SendEventsRequest,
    ): EventsResponse

    @GET("api/v1/forward/sessions/{session_id}/events")
    suspend fun listEvents(
        @Path("session_id") sessionId: String,
        @Query("after_id") afterId: String? = null,
        @Query("limit") limit: Int = 100,
    ): EventsResponse

    @POST("api/v1/forward/sessions/{session_id}/cancel")
    suspend fun cancelSession(@Path("session_id") sessionId: String): SessionDto

    @GET("api/v1/forward/drives/entries")
    suspend fun listDriveEntries(
        @Query("identity_id") identityId: String,
        @Query("path") path: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("page_token") pageToken: String? = null,
    ): DriveEntriesResponse

    @POST("api/v1/forward/drives/upload-url")
    suspend fun uploadUrl(
        @Query("identity_id") identityId: String,
        @Body body: UploadUrlRequest,
    ): PresignedUrlResponse

    @POST("api/v1/forward/drives/download-url")
    suspend fun downloadUrl(
        @Query("identity_id") identityId: String,
        @Body body: DownloadUrlRequest,
    ): PresignedUrlResponse

    @HTTP(method = "DELETE", path = "api/v1/forward/drives/entries", hasBody = true)
    suspend fun deleteDriveEntry(
        @Query("identity_id") identityId: String,
        @Body body: DeleteEntryRequest,
    ): DeleteEntryResponse
}
