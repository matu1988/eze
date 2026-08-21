package com.nanocomm.nanosmart.eventos

object AlertDisplayPolicy {
    const val MAIN_LIMIT = 5
    const val HISTORY_LIMIT = 30

    fun <T> main(items: List<T>): List<T> = items.take(MAIN_LIMIT)

    fun <T> history(items: List<T>): List<T> = items.take(HISTORY_LIMIT)
}
