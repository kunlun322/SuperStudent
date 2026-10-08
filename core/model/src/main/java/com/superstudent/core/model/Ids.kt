package com.superstudent.core.model

import java.security.SecureRandom

/** Time-ordered id generation for pkg_/task_/run_/src_ prefixes (uuidv7-style). */
object Ids {
    private val random = SecureRandom()

    fun uuidV7(): String {
        val millis = System.currentTimeMillis()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[0] = ((millis ushr 40) and 0xFF).toByte()
        bytes[1] = ((millis ushr 32) and 0xFF).toByte()
        bytes[2] = ((millis ushr 24) and 0xFF).toByte()
        bytes[3] = ((millis ushr 16) and 0xFF).toByte()
        bytes[4] = ((millis ushr 8) and 0xFF).toByte()
        bytes[5] = (millis and 0xFF).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    fun pkg(): String = "pkg_" + uuidV7()
    fun task(): String = "task_" + uuidV7()
    fun run(): String = "run_" + uuidV7()
    fun src(): String = "src_" + uuidV7()
}
