package com.nanocomm.nanosmart.eventos

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.atomic.AtomicBoolean

/** Agrupa ráfagas de push en una sola actualización de la interfaz. */
object PushUiRefreshDispatcher {
    private const val REFRESH_WINDOW_MS = 350L
    private val handler = Handler(Looper.getMainLooper())
    private val refreshScheduled = AtomicBoolean(false)

    fun schedule(context: Context) {
        if (!refreshScheduled.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        handler.postDelayed({
            refreshScheduled.set(false)
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(
                Intent(NanoSmartMessagingService.ACTION_PUSH_ALERT)
            )
        }, REFRESH_WINDOW_MS)
    }
}
