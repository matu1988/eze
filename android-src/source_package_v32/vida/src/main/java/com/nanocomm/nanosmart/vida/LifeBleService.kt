package com.nanocomm.nanosmart.vida

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
    private var notificationCharacteristic: BluetoothGattCharacteristic? = null
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
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) {
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                LifePrefs.setConnection(this@LifeBleService, true)
                updateNotification("Botón conectado")
                if (hasConnectPermission()) runCatching { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                LifePrefs.setConnection(this@LifeBleService, false)
                notificationCharacteristic = null
                updateNotification("Botón desconectado · reintentando")
                runCatching { gatt.close() }
                if (this@LifeBleService.gatt === gatt) this@LifeBleService.gatt = null
                mainHandler.postDelayed({ connect(LifePrefs.load(this@LifeBleService)) }, RECONNECT_MS)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = findNotificationCharacteristic(gatt.services)
            if (characteristic == null) {
                updateNotification("Conectado · sin canal de pulsación BLE")
                return
            }
            notificationCharacteristic = characteristic
            enableNotifications(gatt, characteristic)
            mainHandler.postDelayed({ readBattery(gatt) }, 1200L)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleValue(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleValue(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleBattery(characteristic, characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleBattery(characteristic, value)
        }
    }

    private fun findNotificationCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
        return services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull { characteristic ->
                characteristic.uuid != BATTERY_LEVEL_UUID &&
                    (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            }
    }

    @Suppress("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            updateNotification("No se pudo activar la escucha BLE")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun readBattery(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) return
        val battery = gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return
        runCatching { gatt.readCharacteristic(battery) }
    }

    private fun handleBattery(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        if (characteristic.uuid != BATTERY_LEVEL_UUID || value.isNullOrEmpty()) return
        val battery = value[0].toInt() and 0xFF
        LifePrefs.setBattery(this, battery)
        updateNotification(if (LifePrefs.connected(this)) "Botón conectado · batería $battery%" else "Botón desconectado")
    }

    private fun handleValue(value: ByteArray) {
        if (value.isEmpty() || (value[0].toInt() and 0xFF) != 0x01) return
        val now = System.currentTimeMillis()
        if (now - lastPressHandledAt < PRESS_DEBOUNCE_MS) return
        lastPressHandledAt = now
        LifePrefs.setLastPress(this, now)
        val config = LifePrefs.load(this)
        if (!config.validForService()) return
        val event = LifeSender.newEvent(this, config, LifePrefs.battery(this))
        LifePrefs.enqueue(this, event)
        LifePrefs.setServerState(this, "Enviando pedido de ayuda…")
        flushQueue()
    }

    private fun flushQueue() {
        if (!flushing.compareAndSet(false, true)) return
        io.execute {
            try {
                val config = LifePrefs.load(this)
                if (!config.validForService()) return@execute
                val pending = LifePrefs.queue(this)
                val remaining = mutableListOf<PendingLifeEvent>()
                for (event in pending) {
                    try {
                        LifeSender.send(config, event)
                        failureAnnounced.remove(event.requestId)
                        LifePrefs.setServerState(this, "Pedido enviado")
                        announceSuccess()
                    } catch (error: Exception) {
                        remaining.add(event)
                        LifePrefs.setServerState(this, "Pendiente por falta de conexión")
                        if (failureAnnounced.add(event.requestId)) announceNoInternet()
                    }
                }
                LifePrefs.replaceQueue(this, remaining)
            } finally {
                flushing.set(false)
            }
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
            textToSpeech?.speak(
                "Ayuda no enviada por falta de conexión a Internet",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "vida-offline"
            )
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(700L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
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
                flushQueue()
                runCatching { gatt?.let(::readBattery) }
                mainHandler.postDelayed(this, RETRY_MS)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Botón Vida", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene conectado el botón de asistencia por Bluetooth"
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
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
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            val intent = Intent(context, LifeBleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
