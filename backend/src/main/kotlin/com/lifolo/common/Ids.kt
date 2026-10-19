package com.lifolo.common

import java.security.MessageDigest
import java.security.SecureRandom

/** Firestore-style document ids (20 alphanumerics) — the format the apps already handle. */
object Ids {
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun newId(length: Int = 20): String = buildString(length) {
        repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) }
    }

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.toHex()
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
