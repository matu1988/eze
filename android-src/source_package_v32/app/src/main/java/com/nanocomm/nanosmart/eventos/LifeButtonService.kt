package com.nanocomm.nanosmart.eventos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LifeButtonService : Service(), TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val flushing = AtomicBoolean(false)
    private val gattsByImei = ConcurrentHashMap<String, BluetoothGatt>()
    private val lastPressByImei = ConcurrentHashMap<String, Long>()
    private val failureAnnounced = ConcurrentHashMap.newKeySet<String>()
    private var textToSpeech: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        textToSpeech = TextToSpeech(this, this)
        startLifeForeground("Iniciando Botón Vida…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DemoMode.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        connectConfiguredButtons()
        scheduleMaintenance()
        flushQueue()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        gattsByImei.forEach { (imei, gatt) ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
            LifeButtonPrefs.setConnected(this, imei, false)
        }
        gattsByImei.clear()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) textToSpeech?.language = Locale("es", "AR")
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun connectConfiguredButtons() {
        if (!hasConnectPermission()) {
            updateNotification("Falta permiso Bluetooth")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            LifeButtonPrefs.enabledConfigs(this).forEach { LifeButtonPrefs.setConnected(this, it.imei, false) }
            updateNotification("Bluetooth apagado")
            return
        }

        val configured = LifeButtonPrefs.enabledConfigs(this)
        val configuredImeis = configured.mapTo(mutableSetOf()) { it.imei }
        gattsByImei.keys.filter { it !in configuredImeis }.forEach { imei ->
            gattsByImei.remove(imei)?.let { gatt ->
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            LifeButtonPrefs.setConnected(this, imei, false)
        }

        configured.forEach { config ->
            if (gattsByImei.containsKey(config.imei) && LifeButtonPrefs.connected(this, config.imei)) return@forEach
            val device = runCatching { adapter.getRemoteDevice(config.deviceAddress) }.getOrNull()
            if (device == null) {
                LifeButtonPrefs.setConnected(this, config.imei, false)
                return@forEach
            }
            runCatching { gattsByImei.remove(config.imei)?.close() }
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, callback)
            }
            gattsByImei[config.imei] = gatt
        }
        refreshNotificationSummary()
    }

    private fun configForGatt(gatt: BluetoothGatt): LifeButtonConfig? {
        val address = runCatching { gatt.device.address }.getOrNull().orEmpty()
        return LifeButtonPrefs.enabledConfigs(this).firstOrNull {
            it.deviceAddress.equals(address, ignoreCase = true)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val config = configForGatt(gatt) ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LifeButtonPrefs.setConnected(this@LifeButtonService, config.imei, true)
                    refreshNotificationSummary()
                    if (hasConnectPermission()) runCatching { gatt.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    LifeButtonPrefs.setConnected(this@LifeButtonService, config.imei, false)
                    runCatching { gatt.close() }
                    gattsByImei.remove(config.imei, gatt)
                    refreshNotificationSummary()
                    mainHandler.postDelayed({ connectConfiguredButtons() }, RECONNECT_MS)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val config = configForGatt(gatt) ?: return
            val button = gatt.getService(BUTTON_SERVICE_UUID)?.getCharacteristic(BUTTON_CHARACTERISTIC_UUID)
            if (button == null || button.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) {
                LifeButtonPrefs.setConnected(this@LifeButtonService, config.imei, false)
                LifeButtonPrefs.setServerState(this@LifeButtonService, config.imei, "Botón no compatible")
                refreshNotificationSummary()
                return
            }
            if (!enableSubscription(gatt, button, indicate = true)) {
                LifeButtonPrefs.setServerState(this@LifeButtonService, config.imei, "No se pudo activar la escucha")
                return
            }
            mainHandler.postDelayed({ subscribeBattery(gatt) }, 1000L)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            routeChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            routeChanged(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                configForGatt(gatt)?.let { handleBattery(it.imei, characteristic.value) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                configForGatt(gatt)?.let { handleBattery(it.imei, value) }
            }
        }
    }

    private fun routeChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val config = configForGatt(gatt) ?: return
        when (characteristic.uuid) {
            BUTTON_CHARACTERISTIC_UUID -> handleButtonValue(config, value)
            BATTERY_LEVEL_UUID -> handleBattery(config.imei, value)
        }
    }

    @Suppress("MissingPermission")
    private fun enableSubscription(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indicate: Boolean
    ): Boolean {
        if (!hasConnectPermission()) return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return false
        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
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
        mainHandler.postDelayed({ runCatching { gatt.readCharacteristic(battery) } }, 350L)
    }

    private fun handleBattery(imei: String, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        val battery = value[0].toInt() and 0xFF
        if (battery !in 0..100) return
        LifeButtonPrefs.setBattery(this, imei, battery)
        refreshNotificationSummary()

        if (battery <= LOW_BATTERY_PERCENT && !LifeButtonPrefs.batteryAlertSent(this, imei)) {
            io.execute {
                runCatching { LifeButtonSender.sendStatus(this, imei, "BATTERY_LOW", battery) }
                    .onSuccess { LifeButtonPrefs.setBatteryAlertSent(this, imei, true) }
            }
        } else if (battery > LOW_BATTERY_PERCENT && LifeButtonPrefs.batteryAlertSent(this, imei)) {
            io.execute {
                runCatching { LifeButtonSender.sendStatus(this, imei, "BATTERY_RESTORED", battery) }
                    .onSuccess { LifeButtonPrefs.setBatteryAlertSent(this, imei, false) }
            }
        }
    }

    private fun handleButtonValue(config: LifeButtonConfig, value: ByteArray) {
        if (value.size != 1 || (value[0].toInt() and 0xFF) != 0x01) return
        val now = System.currentTimeMillis()
        val previous = lastPressByImei[config.imei] ?: 0L
        if (now - previous < PRESS_DEBOUNCE_MS) return
        lastPressByImei[config.imei] = now
        LifeButtonPrefs.setLastPress(this, config.imei, now)

        val panel = Prefs.panelByImei(this, config.imei) ?: return
        val event = LifeButtonEventFactory.create(this, panel, config)
        LifeButtonPrefs.enqueue(this, event)
        LifeButtonPrefs.setServerState(this, config.imei, "Enviando pedido de ayuda…")

        // El UDP de monitoreo sale inmediatamente. El registro HTTP espera hasta 10 s por una posición nueva.
        flushQueue()
        LifeButtonLocationProvider.requestFresh(this) { location ->
            fun persistWhenIdle() {
                if (flushing.get()) {
                    mainHandler.postDelayed({ persistWhenIdle() }, 100L)
                    return
                }
                LifeButtonPrefs.updateEvent(this, event.requestId) { current ->
                    current.copy(locationReady = true, location = location)
                }
                LifeButtonPrefs.setServerState(
                    this,
                    config.imei,
                    if (location != null) {
                        "Ubicación obtenida · enviando pedido…"
                    } else {
                        "Ubicación no disponible · enviando pedido…"
                    }
                )
                flushQueue()
            }
            persistWhenIdle()
        }
    }

    private fun flushQueue() {
        if (!flushing.compareAndSet(false, true)) return
        io.execute {
            try {
                val remaining = mutableListOf<PendingLifeButtonEvent>()
                val now = System.currentTimeMillis()
                for (queued in LifeButtonPrefs.queue(this)) {
                    var event = if (!queued.locationReady && now - queued.createdAtMillis > LOCATION_FALLBACK_MS) {
                        queued.copy(locationReady = true)
                    } else queued
                    val errors = mutableListOf<Throwable>()

                    if (!event.udpSent) {
                        runCatching { LifeButtonSender.sendUdp(event) }
                            .onSuccess { event = event.copy(udpSent = true) }
                            .onFailure { errors += it }
                    }
                    if (!event.httpSent && event.locationReady) {
                        runCatching { LifeButtonSender.sendHttp(this, event) }
                            .onSuccess { event = event.copy(httpSent = true) }
                            .onFailure { errors += it }
                    }

                    if (event.complete) {
                        failureAnnounced.remove(event.requestId)
                        LifeButtonPrefs.setServerState(
                            this,
                            event.imei,
                            if (event.location != null) {
                                "Pedido enviado · ubicación incluida"
                            } else {
                                "Pedido enviado · sin ubicación"
                            }
                        )
                        announceSuccess()
                    } else {
                        remaining += event
                        if (errors.isNotEmpty()) {
                            LifeButtonPrefs.setServerState(this, event.imei, "Pendiente por falta de conexión")
                            if (failureAnnounced.add(event.requestId)) announceNoInternet()
                        }
                    }
                }
                LifeButtonPrefs.replaceQueue(this, remaining)
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun checkDisconnectAlerts() {
        val now = System.currentTimeMillis()
        LifeButtonPrefs.enabledConfigs(this).forEach { config ->
            if (LifeButtonPrefs.connected(this, config.imei)) return@forEach
            val since = LifeButtonPrefs.disconnectedSince(this, config.imei)
            if (since <= 0L || now - since < DISCONNECT_ALERT_MS ||
                LifeButtonPrefs.disconnectAlertSent(this, config.imei)
            ) return@forEach
            io.execute {
                runCatching {
                    LifeButtonSender.sendStatus(
                        this,
                        config.imei,
                        "DISCONNECTED",
                        LifeButtonPrefs.battery(this, config.imei)
                    )
                }.onSuccess {
                    LifeButtonPrefs.setDisconnectAlertSent(this, config.imei, true)
                }
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
            textToSpeech?.speak(
                "Su pedido de ayuda fue enviado",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "life-button-ok"
            )
        }
    }

    private fun announceNoInternet() {
        mainHandler.post {
            vibrate()
            textToSpeech?.speak(
                "Ayuda no enviada por falta de conexión a Internet",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "life-button-offline"
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
            if (LifeButtonPrefs.enabledConfigs(this@LifeButtonService).isEmpty()) {
                stopSelf()
                return
            }
            connectConfiguredButtons()
            checkDisconnectAlerts()
            flushQueue()
            gattsByImei.values.forEach { gatt -> runCatching { subscribeBattery(gatt) } }
            mainHandler.postDelayed(this, RETRY_MS)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Botón Vida", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene conectado el botón de asistencia"
                setSound(null, null)
            }
        )
    }

    private fun startLifeForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectedType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val locationType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            val started = runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    connectedType or locationType
                )
            }.isSuccess
            if (!started) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, connectedType)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            640,
            Intent(this, PanelsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nanosmart)
            .setContentTitle("NanoSmart · Botón Vida")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun refreshNotificationSummary() {
        val configs = LifeButtonPrefs.enabledConfigs(this)
        val connected = configs.count { LifeButtonPrefs.connected(this, it.imei) }
        val text = when {
            configs.isEmpty() -> "Sin botones habilitados"
            configs.size == 1 && connected == 1 -> {
                val battery = LifeButtonPrefs.battery(this, configs.first().imei)
                if (battery != null) "Botón conectado · batería $battery%" else "Botón conectado"
            }
            configs.size == 1 -> "Botón desconectado · reintentando"
            else -> "$connected de ${configs.size} botones conectados"
        }
        updateNotification(text)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "nanosmart_life_button"
        private const val NOTIFICATION_ID = 1640
        private const val RECONNECT_MS = 10_000L
        private const val RETRY_MS = 30_000L
        private const val PRESS_DEBOUNCE_MS = 1_500L
        private const val LOCATION_FALLBACK_MS = 12_000L
        private const val DISCONNECT_ALERT_MS = 120_000L
        private const val LOW_BATTERY_PERCENT = 20

        private val BUTTON_SERVICE_UUID = UUID.fromString("ccaf68a3-dd38-4c61-bfd2-9b14027605ea")
        private val BUTTON_CHARACTERISTIC_UUID = UUID.fromString("1f1e4671-b051-4a30-837c-86f3b11cc5ae")
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            if (DemoMode.enabled || LifeButtonPrefs.enabledConfigs(context).isEmpty()) return
            ContextCompat.startForegroundService(context, Intent(context, LifeButtonService::class.java))
        }

        fun stopIfUnused(context: Context) {
            if (LifeButtonPrefs.enabledConfigs(context).isEmpty()) {
                context.stopService(Intent(context, LifeButtonService::class.java))
            } else {
                start(context)
            }
        }
    }
}
