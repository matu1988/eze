package com.nanocomm.nanosmart.eventos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build

object NotificationChannels {
    fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NanoSmartMessagingService.CHANNEL_ID,
                "Alertas de alarma",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Disparos y eventos de los equipos NanoSmart"
                enableVibration(true)
            }
        )
    }

    fun ensureAudibleAlarmChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                AudibleAlarmService.CHANNEL_ID,
                "Alarma activa",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarma sonora por Médica, Pánico, Incendio o Robo"
                enableVibration(false)
                setSound(null, AudioAttributes.Builder().build())
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
