package com.nanocomm.nanosmart.eventos

import android.os.SystemClock

object AppLockPolicy {
    const val BACKGROUND_TIMEOUT_MS = 30_000L

    fun shouldLock(backgroundDurationMs: Long): Boolean =
        backgroundDurationMs >= BACKGROUND_TIMEOUT_MS
}

object AppLockState {
    @Volatile
    private var unlocked = false

    @Volatile
    private var backgroundAtMs: Long? = null

    val isUnlocked: Boolean
        get() = unlocked

    fun unlock() {
        unlocked = true
        backgroundAtMs = null
    }

    fun lock() {
        unlocked = false
    }

    fun appEnteredBackground() {
        backgroundAtMs = SystemClock.elapsedRealtime()
    }

    fun appEnteredForeground() {
        val backgroundAt = backgroundAtMs ?: return
        val elapsed = (SystemClock.elapsedRealtime() - backgroundAt).coerceAtLeast(0L)
        if (AppLockPolicy.shouldLock(elapsed)) lock()
        backgroundAtMs = null
    }
}
