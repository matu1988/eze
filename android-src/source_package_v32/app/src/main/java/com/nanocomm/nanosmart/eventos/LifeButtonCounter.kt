package com.nanocomm.nanosmart.eventos

import android.content.Context

/**
 * Usa exactamente la misma clave de contador que Prefs.nextCounterForSend(), pero permite
 * incrementar el contador de un panel específico desde el servicio en segundo plano.
 */
fun Prefs.nextCounterForSend(context: Context, imei: String): Int {
    val storage = context.getSharedPreferences("nanosmart_eventos_prefs", Context.MODE_PRIVATE)
    val normalizedImei = imei.trim().ifBlank { "none" }
    val key = "counter_$normalizedImei"
    val current = storage.getInt(key, 60)
    storage.edit().putInt(key, if (current >= 90) 60 else current + 1).apply()
    return current
}
