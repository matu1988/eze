package com.nanocomm.nanosmart.eventos

object AppVisibility {
    @Volatile
    private var visibleActivities = 0

    val isForeground: Boolean
        get() = visibleActivities > 0

    fun activityStarted() {
        visibleActivities += 1
    }

    fun activityStopped() {
        visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
    }
}
