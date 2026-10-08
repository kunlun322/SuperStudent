package com.superstudent.core.drive

import kotlinx.serialization.KSerializer

/**
 * The slice of Drive the two cloud manifests need: read a JSON object if it exists, write one.
 *
 * [DriveRepository] is final and its transport is a presigned PUT over OkHttp, so the manifest write
 * path has no other way to be exercised on the JVM. This is a seam with one production implementation,
 * not a mock — the writer still talks to the same Drive, through the same two calls.
 */
interface ManifestStore {
    suspend fun <T> readJsonOrNull(identityId: String, path: String, serializer: KSerializer<T>): T?

    suspend fun <T> writeJson(identityId: String, path: String, value: T, serializer: KSerializer<T>)
}
