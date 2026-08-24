package com.nanocomm.nanosmart.eventos

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class NanoSmartMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistration.syncToken(this, token, force = true)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val imei = message.data["imei"]?.trim().orEmpty()
        val baseTitle = message.notification?.title
            ?: message.data["title"]
            ?: "Alerta NanoSmart"
        val panelName = Prefs.panelByImei(this, imei)?.panelName
        val title = panelName?.let { "$it · $baseTitle" } ?: baseTitle
        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["eventDescription"]
            ?: "Se recibió un nuevo evento de alarma"
        val alertId = message.data["alertId"]?.toLongOrNull() ?: System.currentTimeMillis()
        val actorName = message.data["actorName"]?.trim()?.takeIf { it.isNotEmpty() }
        val actionSource = message.data["actionSource"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val latitude = message.data["latitude"]?.toDoubleOrNull()
        val longitude = message.data["longitude"]?.toDoubleOrNull()
        val hasValidLocation = latitude != null && longitude != null &&
            EmergencyLocationPolicy.validCoordinates(latitude, longitude)

        // Protección de respaldo: cualquier 640 que vuelva al mismo teléfono inmediatamente
        // después de una pulsación física local se considera eco propio, venga por HTTP o UDP.
        val lastLifeButtonPress = if (imei.isNotEmpty()) LifeButtonPrefs.lastPress(this, imei) else 0L
        val lifeButtonEchoAge = System.currentTimeMillis() - lastLifeButtonPress
        val isOwnLifeButtonEcho = message.data["eventCode"]?.trim() == "640" &&
            lastLifeButtonPress > 0L &&
            lifeButtonEchoAge in 0..OWN_LIFE_BUTTON_ECHO_WINDOW_MS

        val deviceStatus = message.data["panelStatus"]
            ?.trim()
            ?.uppercase()
            ?.takeIf { it == "ARMADO" || it == "DESARMADO" }
            ?: when (message.data["action"]?.uppercase()) {
                "ARMAR" -> "ARMADO"
                "DESARMAR" -> "DESARMADO"
                else -> null
            }
        if (message.data["type"] in setOf("DEVICE_COMMAND", "PANEL_STATE") && deviceStatus != null) {
            if (imei.isNotEmpty()) {
                Prefs.setStatusForImei(this, imei, deviceStatus)
                Prefs.setLastActionActorForImei(this, imei, actorName, actionSource)
            }
        }

        if (isOwnLifeButtonEcho) {
            PushUiRefreshDispatcher.schedule(this)
            return
        }

        val shouldSoundAlarm = AudibleAlarmPolicy.shouldSound(message.data)
        val alarmStarted = shouldSoundAlarm &&
            !AppVisibility.isForeground &&
            message.priority == RemoteMessage.PRIORITY_HIGH &&
            AudibleAlarmService.start(
                this,
                title,
                body,
                alertId,
                imei,
                latitude.takeIf { hasValidLocation },
                longitude.takeIf { hasValidLocation }
            )

        if (!alarmStarted && !AppVisibility.isForeground) {
            showNotification(
                title,
                body,
                alertId,
                imei,
                latitude.takeIf { hasValidLocation },
                longitude.takeIf { hasValidLocation }
            )
        }
        PushUiRefreshDispatcher.schedule(this)
    }

    private fun showNotification(
        title: String,
        body: String,
        alertId: Long,
        imei: String,
        latitude: Double?,
        longitude: Double?
    ) {
        NotificationChannels.ensureAlertChannel(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ALERT_ID, alertId)
            putExtra(MainActivity.EXTRA_PANEL_IMEI, imei)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            31 * alertId.hashCode() + imei.hashCode(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nanosmart)
            .setColor(getColor(R.color.m41_red))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setContentIntent(pendingIntent)

        if (latitude != null && longitude != null) {
            val mapsIntent = PendingIntent.getActivity(
                this,
                47 * alertId.hashCode() + imei.hashCode(),
                EmergencyMapLink.intent(latitude, longitude),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            notification.addAction(
                R.drawable.ic_nanosmart,
                "Ver ubicación",
                mapsIntent
            )
        }

        manager.notify(alertId.hashCode(), notification.build())
    }

    companion object {
        private const val OWN_LIFE_BUTTON_ECHO_WINDOW_MS = 30_000L
        const val CHANNEL_ID = "nanosmart_alertas"
        const val ACTION_PUSH_ALERT = "com.nanocomm.nanosmart.eventos.PUSH_ALERT"
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_IMEI = "imei"
        const val EXTRA_DEVICE_STATUS = "device_status"
        const val EXTRA_ACTOR_NAME = "actor_name"
        const val EXTRA_ACTION_SOURCE = "action_source"
    }
}
