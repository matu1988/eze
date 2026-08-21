package com.nanocomm.nanosmart.eventos

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class AudibleAlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private val toneHandler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* La alarma sigue activa. */ }
    private val fallbackTone = object : Runnable {
        override fun run() {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_000)
            toneHandler.postDelayed(this, 1_500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureAudibleAlarmChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activeAlarm = Prefs.activeAudibleAlarm(this)
        if (activeAlarm == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(activeAlarm)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        if (mediaPlayer == null && toneGenerator == null) {
            requestAudioFocus()
            startVibration()
            startAlarmSound()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarmOutputs()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(alarm: ActiveAudibleAlarm): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NanoSmartMessagingService.EXTRA_ALERT_ID, alarm.alertId)
            putExtra(MainActivity.EXTRA_PANEL_IMEI, alarm.imei)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nanosmart)
            .setColor(ContextCompat.getColor(this, R.color.m41_red))
            .setContentTitle(alarm.title)
            .setContentText(alarm.body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${alarm.body}\n\nAbrí NanoSmart para detener la alarma.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)

        val latitude = alarm.latitude
        val longitude = alarm.longitude
        if (latitude != null && longitude != null &&
            EmergencyLocationPolicy.validCoordinates(latitude, longitude)
        ) {
            val mapsIntent = PendingIntent.getActivity(
                this,
                NOTIFICATION_ID + 1,
                EmergencyMapLink.intent(latitude, longitude),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            notification.addAction(
                R.drawable.ic_nanosmart,
                "Ver ubicación",
                mapsIntent
            )
        }
        return notification.build()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAudioAttributes())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun startAlarmSound() {
        if (!startBundledAlarmSound()) {
            startSystemAlarmSound()
        }
    }

    private fun startBundledAlarmSound(): Boolean = runCatching {
        MediaPlayer().also { player ->
            mediaPlayer = player
            player.setAudioAttributes(alarmAudioAttributes())
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            resources.openRawResourceFd(R.raw.alarma_nanosmart).use { descriptor ->
                checkNotNull(descriptor) { "No se pudo abrir la sirena incluida" }
                player.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )
            }
            player.isLooping = true
            player.setOnPreparedListener { it.start() }
            player.setOnErrorListener { failedPlayer, _, _ ->
                runCatching { failedPlayer.release() }
                if (mediaPlayer === failedPlayer) mediaPlayer = null
                startSystemAlarmSound()
                true
            }
            player.prepareAsync()
        }
    }.fold(
        onSuccess = { true },
        onFailure = {
            mediaPlayer?.runCatching { release() }
            mediaPlayer = null
            false
        }
    )

    private fun startSystemAlarmSound() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (alarmUri == null) {
            startFallbackTone()
            return
        }

        runCatching {
            MediaPlayer().also { player ->
                mediaPlayer = player
                player.setAudioAttributes(alarmAudioAttributes())
                player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                player.setDataSource(applicationContext, alarmUri)
                player.isLooping = true
                player.setOnPreparedListener { it.start() }
                player.setOnErrorListener { failedPlayer, _, _ ->
                    failedPlayer.release()
                    if (mediaPlayer === failedPlayer) mediaPlayer = null
                    startFallbackTone()
                    true
                }
                player.prepareAsync()
            }
        }.onFailure {
            mediaPlayer?.release()
            mediaPlayer = null
            startFallbackTone()
        }
    }

    private fun startFallbackTone() {
        if (toneGenerator != null) return
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
        if (toneGenerator != null) {
            toneHandler.post(fallbackTone)
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 400, 800, 1_200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopAlarmOutputs() {
        toneHandler.removeCallbacks(fallbackTone)
        toneGenerator?.release()
        toneGenerator = null
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun alarmAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    companion object {
        const val CHANNEL_ID = "nanosmart_alarma_activa_v1"
        private const val NOTIFICATION_ID = 91_001

        fun start(
            context: Context,
            title: String,
            body: String,
            alertId: Long,
            imei: String,
            latitude: Double?,
            longitude: Double?
        ): Boolean {
            Prefs.setActiveAudibleAlarm(
                context,
                title,
                body,
                alertId,
                imei,
                latitude,
                longitude
            )
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AudibleAlarmService::class.java)
                )
            }.isSuccess
        }

        fun stop(context: Context) {
            Prefs.clearActiveAudibleAlarm(context)
            context.stopService(Intent(context, AudibleAlarmService::class.java))
        }
    }
}
