package com.nanocomm.nanosmart.vida

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LifeBleService : Service(), TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val flushing = AtomicBoolean(false)
    private val failureAnnounced = mutableSetOf<String>()
    private var gatt: BluetoothGatt? = null
    private var textToSpeech: TextToSpeech? = null
    private var lastPressHandledAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        textToSpeech = TextToSpeech(this, this)
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando Botón Vida…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = LifePrefs.load(this)
        if (!config.validForService()) {
            LifePrefs.setConnection(this, false)
            updateNotification("Configuración incompleta")
            return START_STICKY
        }
        connect(config)
        scheduleMaintenance()
        flushQueue()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        io.shutdownNow()
        LifePrefs.setConnection(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) textToSpeech?.language = Locale("es", "AR")
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun connect(config: LifeConfig) {
        if (!hasConnectPermission()) {
            LifePrefs.setConnection(this, false)
            updateNotification("Falta permiso Bluetooth")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            LifePrefs.setConnection(this, false)
            updateNotification("Bluetooth apagado")
            return
        }
        val current = gatt
        if (current != null && LifePrefs.connected(this)) return
        runCatching { current?.close() }
        val device = runCatching { adapter.getRemoteDevice(config.deviceAddress) }.getOrNull()
        if (device == null) {
            LifePrefs.setConnection(this, false)
            updateNotification("Botón vinculado no disponible")
            return
        }
        updateNotification("Conectando a ${config.deviceName.ifBlank { "Botón Vida" }}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, callback, BluetoothGatt.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, callback)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LifePrefs.setConnection(this@LifeBleService, true)
                    LifePrefs.setDisconnectAlertSent(this@LifeBleService, false)
                    updateNotification("Botón conectado")
                    if (hasConnectPermission()) runCatching { gatt.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    LifePrefs.setConnection(this@LifeBleService, false)
                    updateNotification("Botón desconectado · reintentando")
                    runCatching { gatt.close() }
                    if (this@LifeBleService.gatt === gatt) this@LifeBleService.gatt = null
                    mainHandler.postDelayed({ connect(LifePrefs.load(this@LifeBleService)) }, RECONNECT_MS)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val button = gatt.getService(BUTTON_SERVICE_UUID)?.getCharacteristic(BUTTON_CHARACTERISTIC_UUID)
            if (button == null || button.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) {
                LifePrefs.setConnection(this@LifeBleService, false)
                updateNotification("El dispositivo no es un Climax BL3 compatible")
                return
            }
            if (!enableSubscription(gatt, button, indicate = true)) {
                updateNotification("No se pudo activar el canal de pulsación")
                return
            }
            mainHandler.postDelayed({ subscribeBattery(gatt) }, 900L)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            routeChanged(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            routeChanged(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                handleBattery(characteristic.value)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) handleBattery(value)
        }
    }

    private fun routeChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        when (characteristic.uuid) {
            BUTTON_CHARACTERISTIC_UUID -> handleButtonValue(value)
            BATTERY_LEVEL_UUID -> handleBattery(value)
        }
    }

    @Suppress("MissingPermission")
    private fun enableSubscription(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, indicate: Boolean): Boolean {
        if (!hasConnectPermission()) return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun subscribeBattery(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) return
        val battery = gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return
        enableSubscription(gatt, battery, indicate = false)
        runCatching { gatt.readCharacteristic(battery) }
    }

    private fun handleBattery(value: ByteArray?) {
        if (value.isNullOrEmpty()) return
        val battery = value[0].toInt() and 0xFF
        if (battery !in 0..100) return
        LifePrefs.setBattery(this, battery)
        if (battery <= LOW_BATTERY_PERCENT && !LifePrefs.batteryLowAlertSent(this)) {
            LifePrefs.setBatteryLowAlertSent(this, true)
            sendLifeStatus("BATTERY_LOW", battery)
        } else if (battery > LOW_BATTERY_PERCENT && LifePrefs.batteryLowAlertSent(this)) {
            LifePrefs.setBatteryLowAlertSent(this, false)
            sendLifeStatus("BATTERY_RESTORED", battery)
        }
        updateNotification(if (LifePrefs.connected(this)) "Botón conectado · batería $battery%" else "Botón desconectado")
    }

    private fun handleButtonValue(value: ByteArray) {
        if (value.size != 1 || (value[0].toInt() and 0xFF) != 0x01) return
        val now = System.currentTimeMillis()
        if (now - lastPressHandledAt < PRESS_DEBOUNCE_MS) return
        lastPressHandledAt = now
        LifePrefs.setLastPress(this, now)
        val config = LifePrefs.load(this)
        if (!config.validForService()) return
        LifePrefs.enqueue(this, LifeSender.newEvent(this, config, LifePrefs.battery(this)))
        LifePrefs.setServerState(this, "Enviando pedido de ayuda…")
        flushQueue()
    }

    private fun flushQueue() {
        if (!flushing.compareAndSet(false, true)) return
        io.execute {
            try {
                val config = LifePrefs.load(this)
                if (!config.validForService()) return@execute
                val remaining = mutableListOf<PendingLifeEvent>()
                for (pending in LifePrefs.queue(this)) {
                    val result = LifeSender.attempt(config, pending)
                    if (result.event.complete) {
                        failureAnnounced.remove(pending.requestId)
                        LifePrefs.setServerState(this, "Pedido enviado")
                        announceSuccess()
                    } else {
                        remaining += result.event
                        LifePrefs.setServerState(this, "Pendiente por falta de conexión")
                        if (failureAnnounced.add(pending.requestId)) announceNoInternet()
                    }
                }
                LifePrefs.replaceQueue(this, remaining)
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun sendLifeStatus(type: String, battery: Int? = LifePrefs.battery(this)) {
        val config = LifePrefs.load(this)
        if (!config.validForService()) return
        io.execute {
            runCatching { LifeSender.sendStatus(config, type, battery) }
        }
    }

    private fun checkDisconnectAlert() {
        if (LifePrefs.connected(this)) return
        val since = LifePrefs.disconnectedSince(this)
        if (since > 0L && System.currentTimeMillis() - since >= DISCONNECT_ALERT_MS && !LifePrefs.disconnectAlertSent(this)) {
            LifePrefs.setDisconnectAlertSent(this, true)
            sendLifeStatus("DISCONNECTED")
        }
    }

    private fun announceSuccess() {
        mainHandler.post {
            vibrate()
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
                startTone(ToneGenerator.TONE_PROP_ACK, 500)
                mainHandler.postDelayed({ release() }, 700L)
            }
            textToSpeech?.speak("Su pedido de ayuda fue enviado", TextToSpeech.QUEUE_FLUSH, null, "vida-ok")
        }
    }

    private fun announceNoInternet() {
        mainHandler.post {
            vibrate()
            textToSpeech?.speak("Ayuda no enviada por falta de conexión a Internet", TextToSpeech.QUEUE_FLUSH, null, "vida-offline")
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(700L, VibrationEffect.DEFAULT_AMPLITUDE))
        else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(700L)
        }
    }

    private fun scheduleMaintenance() {
        mainHandler.removeCallbacks(maintenance)
        mainHandler.postDelayed(maintenance, RETRY_MS)
    }

    private val maintenance = object : Runnable {
        override fun run() {
            val config = LifePrefs.load(this@LifeBleService)
            if (config.enabled) {
                if (!LifePrefs.connected(this@LifeBleService)) connect(config)
                checkDisconnectAlert()
                flushQueue()
                runCatching { gatt?.let(::subscribeBattery) }
                mainHandler.postDelayed(this, RETRY_MS)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Botón Vida", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene conectado el botón de asistencia por Bluetooth"
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NanoSmart Botón Vida")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "nanosmart_vida_service"
        private const val NOTIFICATION_ID = 640
        private const val RECONNECT_MS = 10_000L
        private const val RETRY_MS = 30_000L
        private const val PRESS_DEBOUNCE_MS = 1500L
        private const val DISCONNECT_ALERT_MS = 120_000L
        private const val LOW_BATTERY_PERCENT = 20
        private val BUTTON_SERVICE_UUID = UUID.fromString("ccaf68a3-dd38-4c61-bfd2-9b14027605ea")
        private val BUTTON_CHARACTERISTIC_UUID = UUID.fromString("1f1e4671-b051-4a30-837c-86f3b11cc5ae")
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LifeBleService::class.java))
        }
    }
}
