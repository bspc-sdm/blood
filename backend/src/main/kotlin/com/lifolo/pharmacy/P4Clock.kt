package com.lifolo.pharmacy

import org.springframework.stereotype.Component

/**
 * "Now" for the pharmacy/books code (Node: Date.now()). Tests pin it (the Node tests use support/clock.js
 * setDhaka) to check opening hours at night; production never sets [fixed].
 */
@Component
class P4Clock {
    @Volatile
    var fixed: Long? = null

    fun now(): Long = fixed ?: System.currentTimeMillis()
}
