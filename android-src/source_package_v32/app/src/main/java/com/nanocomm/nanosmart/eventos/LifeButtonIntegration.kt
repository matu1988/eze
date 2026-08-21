package com.nanocomm.nanosmart.eventos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class LifeButtonConfig(
    val imei: String,
    val enabled: Boolean,
    val deviceAddress: String,
    val deviceName: String
)

data class PendingLifeButtonEvent(
    val requestId: String,
    val imei: String,
    val createdAtMillis: Long,
    val udpPayload: String?,
    val monitoringIp: String,
    val monitoringPort: Int,
    val buttonAddress: String,
    val buttonBattery: Int?,
    val locationReady: Boolean,
    val location: EmergencyLocation?,
    val udpSent: Boolean,
    val httpSent: Boolean
) {
    val complete: Boolean get() = udpSent && httpSent

    fun toJson(): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("imei", imei)
        .put("createdAtMillis", createdAtMillis)
        .put("udpPayload", udpPayload ?: JSONObject.NULL)
        .put("monitoringIp", monitoringIp)
        .put("monitoringPort", monitoringPort)
        .put("buttonAddress", buttonAddress)
        .put("buttonBattery", buttonBattery ?: JSONObject.NULL)
        .put("locationReady", locationReady)
        .put("udpSent", udpSent)
        .put("httpSent", httpSent)
        .apply {
            location?.let {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("locationAccuracyMeters", it.accuracyMeters?.toDouble() ?: JSONObject.NULL)
                put("locationCapturedAtMillis", it.capturedAtMillis)
            }
        }

    companion object {
        fun fromJson(json: JSONObject): PendingLifeButtonEvent? = runCatching {
            val location = if (json.has("latitude") && json.has("longitude")) {
                EmergencyLocation(
                    latitude = json.getDouble("latitude"),
                    longitude = json.getDouble("longitude"),
                    accuracyMeters = if (json.isNull("locationAccuracyMeters")) null
                    else json.getDouble("locationAccuracyMeters").toFloat(),
                    capturedAtMillis = json.optLong("locationCapturedAtMillis", json.getLong("createdAtMillis"))
                )
            } else null
            PendingLifeButtonEvent(
                requestId = json.getString("requestId"),
                imei = json.getString("imei"),
                createdAtMillis = json.getLong("createdAtMillis"),
                udpPayload = if (json.isNull("udpPayload")) null else json.optString("udpPayload"),
                monitoringIp = json.optString("monitoringIp"),
                monitoringPort = json.optInt("monitoringPort", 0),
                buttonAddress = json.optString("buttonAddress"),
                buttonBattery = if (json.isNull("buttonBattery")) null else json.optInt("buttonBattery"),
                locationReady = json.optBoolean("locationReady", false),
                location = location,
                udpSent = json.optBoolean("udpSent", false),
                httpSent = json.optBoolean("httpSent", false)
            )
        }.getOrNull()
    }
}

object LifeButtonPrefs {
    private const val FILE = "nanosmart_life_button_integrated"
    private const val QUEUE = "pending_events"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(prefix: String, imei: String) = "${prefix}_${imei.trim()}"

    fun config(context: Context, imei: String): LifeButtonConfig = LifeButtonConfig(
        imei = imei.trim(),
        enabled = prefs(context).getBoolean(key("enabled", imei), false),
        deviceAddress = prefs(context).getString(key("address", imei), "").orEmpty(),
        deviceName = prefs(context).getString(key("name", imei), "").orEmpty()
    )

    fun enabledConfigs(context: Context): List<LifeButtonConfig> = Prefs.panels(context)
        .map { config(context, it.imei) }
        .filter { it.enabled && it.deviceAddress.isNotBlank() }

    fun setEnabled(context: Context, imei: String, enabled: Boolean) {
        if (imei.isBlank()) return
        prefs(context).edit().putBoolean(key("enabled", imei), enabled).apply()
    }

    fun saveDevice(context: Context, imei: String, address: String, name: String) {
        if (imei.isBlank()) return
        prefs(context).edit()
            .putString(key("address", imei), address.trim())
            .putString(key("name", imei), name.trim())
            .apply()
    }

    fun migrate(context: Context, oldImei: String?, newImei: String) {
        val old = oldImei?.trim().orEmpty()
        val target = newImei.trim()
        if (old.isBlank() || target.isBlank() || old == target) return
        val previous = config(context, old)
        prefs(context).edit()
            .putBoolean(key("enabled", target), previous.enabled)
            .putString(key("address", target), previous.deviceAddress)
            .putString(key("name", target), previous.deviceName)
            .remove(key("enabled", old))
            .remove(key("address", old))
            .remove(key("name", old))
            .apply()
    }

    fun connected(context: Context, imei: String): Boolean =
        prefs(context).getBoolean(key("connected", imei), false)

    fun setConnected(context: Context, imei: String, connected: Boolean) {
        if (imei.isBlank()) return
        val storage = prefs(context)
        val editor = storage.edit().putBoolean(key("connected", imei), connected)
        if (connected) {
            editor.remove(key("disconnectedSince", imei))
                .putBoolean(key("disconnectAlertSent", imei), false)
        } else if (storage.getLong(key("disconnectedSince", imei), 0L) == 0L) {
            editor.putLong(key("disconnectedSince", imei), System.currentTimeMillis())
        }
        editor.apply()
    }

    fun disconnectedSince(context: Context, imei: String): Long =
        prefs(context).getLong(key("disconnectedSince", imei), 0L)

    fun disconnectAlertSent(context: Context, imei: String): Boolean =
        prefs(context).getBoolean(key("disconnectAlertSent", imei), false)

    fun setDisconnectAlertSent(context: Context, imei: String, sent: Boolean) =
        prefs(context).edit().putBoolean(key("disconnectAlertSent", imei), sent).apply()

    fun battery(context: Context, imei: String): Int? {
        val storage = prefs(context)
        val k = key("battery", imei)
        return if (storage.contains(k)) storage.getInt(k, 0) else null
    }

    fun setBattery(context: Context, imei: String, battery: Int) =
        prefs(context).edit().putInt(key("battery", imei), battery.coerceIn(0, 100)).apply()

    fun batteryAlertSent(context: Context, imei: String): Boolean =
        prefs(context).getBoolean(key("batteryAlertSent", imei), false)

    fun setBatteryAlertSent(context: Context, imei: String, sent: Boolean) =
        prefs(context).edit().putBoolean(key("batteryAlertSent", imei), sent).apply()

    fun lastPress(context: Context, imei: String): Long =
        prefs(context).getLong(key("lastPress", imei), 0L)

    fun setLastPress(context: Context, imei: String, value: Long) =
        prefs(context).edit().putLong(key("lastPress", imei), value).apply()

    fun serverState(context: Context, imei: String): String =
        prefs(context).getString(key("serverState", imei), "Sin comprobar").orEmpty()

    fun setServerState(context: Context, imei: String, state: String) =
        prefs(context).edit().putString(key("serverState", imei), state).apply()

    @Synchronized
    fun queue(context: Context): MutableList<PendingLifeButtonEvent> {
        val raw = prefs(context).getString(QUEUE, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    PendingLifeButtonEvent.fromJson(array.getJSONObject(index))?.let(::add)
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun replaceQueue(context: Context, events: List<PendingLifeButtonEvent>) {
        val array = JSONArray()
        events.takeLast(100).forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(QUEUE, array.toString()).apply()
    }

    @Synchronized
    fun enqueue(context: Context, event: PendingLifeButtonEvent) {
        val events = queue(context)
        if (events.none { it.requestId == event.requestId }) events += event
        replaceQueue(context, events)
    }

    @Synchronized
    fun updateEvent(
        context: Context,
        requestId: String,
        transform: (PendingLifeButtonEvent) -> PendingLifeButtonEvent
    ) {
        val events = queue(context)
        val index = events.indexOfFirst { it.requestId == requestId }
        if (index < 0) return
        events[index] = transform(events[index])
        replaceQueue(context, events)
    }
}

object LifeButtonPacketBuilder {
    const val CONTACT_ID_BLOCK = "181640010000"

    fun build(context: Context, panel: PanelConfig): String {
        val date = SimpleDateFormat("dd/MM/yyyy-HH:mm", Locale.getDefault()).format(Date())
        val counter = String.format(Locale.US, "%02d", Prefs.nextCounterForSend(context, panel.imei))
        return buildString {
            append("$")
            append("B,")
            append(panel.id)
            append(',')
            append(counter)
            append(',')
            append(date)
            append(",01,")
            append(panel.abonado)
            append(CONTACT_ID_BLOCK)
            append(",18,0,0,")
            append(panel.clave)
            append(",15,MA_1.90GE-AR,0,0,0,0,0,0,0,")
            append(panel.imei)
            append(",0,0,")
            append(panel.ip)
            append(',')
            append(panel.port)
            append(",00,10,4G,")
            append("$")
            append('E')
        }
    }
}

object LifeButtonEventFactory {
    fun create(context: Context, panel: PanelConfig, config: LifeButtonConfig): PendingLifeButtonEvent {
        val needsUdp = panel.serviceMode == ServiceMode.MONITORING
        return PendingLifeButtonEvent(
            requestId = "vida-${UUID.randomUUID()}",
            imei = panel.imei,
            createdAtMillis = System.currentTimeMillis(),
            udpPayload = if (needsUdp) LifeButtonPacketBuilder.build(context, panel) else null,
            monitoringIp = panel.ip,
            monitoringPort = panel.port,
            buttonAddress = config.deviceAddress,
            buttonBattery = LifeButtonPrefs.battery(context, panel.imei),
            locationReady = false,
            location = null,
            udpSent = !needsUdp,
            httpSent = false
        )
    }
}

object LifeButtonSender {
    fun sendUdp(event: PendingLifeButtonEvent) {
        val payload = event.udpPayload ?: return
        require(event.monitoringIp.isNotBlank() && event.monitoringPort in 1..65535) {
            "faltan IP o puerto de monitoreo"
        }
        val data = payload.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            socket.send(
                DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(event.monitoringIp),
                    event.monitoringPort
                )
            )
        }
    }

    fun sendHttp(context: Context, event: PendingLifeButtonEvent) {
        val panel = Prefs.panelByImei(context, event.imei)
            ?: error("El panel ${event.imei} ya no está configurado")
        if (panel.accessToken.isBlank()) error("Falta el token NanoSmart del panel")
        AlertApiClient.sendLifeEmergency(
            accessToken = panel.accessToken,
            name = Prefs.name(context),
            panelName = panel.panelName,
            abonado = panel.abonado,
            requestId = event.requestId,
            buttonId = event.buttonAddress,
            buttonBattery = event.buttonBattery,
            location = event.location
        )
    }

    fun sendStatus(context: Context, imei: String, status: String, battery: Int?) {
        val panel = Prefs.panelByImei(context, imei) ?: return
        if (panel.accessToken.isBlank()) return
        AlertApiClient.sendLifeStatus(
            accessToken = panel.accessToken,
            status = status,
            panelName = panel.panelName,
            buttonId = LifeButtonPrefs.config(context, imei).deviceAddress,
            buttonBattery = battery,
            name = Prefs.name(context)
        )
    }
}

object LifeButtonLocationProvider {
    private const val TIMEOUT_MS = 10_000L
    private const val MAX_CURRENT_AGE_MS = 60_000L
    private const val MAX_FALLBACK_AGE_MS = 60_000L

    @SuppressLint("MissingPermission")
    fun requestFresh(context: Context, callback: (EmergencyLocation?) -> Unit) {
        val appContext = context.applicationContext
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            callback(null)
            return
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabledProviders = buildList {
            if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            if (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }.distinct()

        fun accepted(location: Location?, maxAgeMs: Long): EmergencyLocation? {
            if (location == null) return null
            val now = System.currentTimeMillis()
            val capturedAt = location.time.takeIf { it > 0L } ?: now
            val age = (now - capturedAt.coerceAtMost(now)).coerceAtLeast(0L)
            if (age > maxAgeMs) return null
            if (!EmergencyLocationPolicy.validCoordinates(location.latitude, location.longitude)) return null
            return EmergencyLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                capturedAtMillis = capturedAt
            )
        }

        fun recentLastKnown(): EmergencyLocation? {
            val candidates = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.mapNotNull { accepted(it, MAX_FALLBACK_AGE_MS) }
            return candidates.minWithOrNull(
                compareBy<EmergencyLocation> { it.accuracyMeters ?: Float.MAX_VALUE }
                    .thenByDescending { it.capturedAtMillis }
            )
        }

        if (enabledProviders.isEmpty()) {
            callback(recentLastKnown())
            return
        }

        val delivered = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val cancellations = mutableListOf<CancellationSignal>()

        fun finish(location: EmergencyLocation?) {
            if (!delivered.compareAndSet(false, true)) return
            cancellations.forEach { runCatching { it.cancel() } }
            callback(location ?: recentLastKnown())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            enabledProviders.forEach { provider ->
                val cancellation = CancellationSignal()
                cancellations += cancellation
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        cancellation,
                        ContextCompat.getMainExecutor(appContext)
                    ) { location ->
                        accepted(location, MAX_CURRENT_AGE_MS)?.let(::finish)
                    }
                }
            }
            handler.postDelayed({ finish(null) }, TIMEOUT_MS)
            return
        }

        @Suppress("DEPRECATION")
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                accepted(location, MAX_CURRENT_AGE_MS)?.let {
                    runCatching { manager.removeUpdates(this) }
                    finish(it)
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        enabledProviders.forEach { provider ->
            runCatching {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
        handler.postDelayed({
            runCatching { manager.removeUpdates(listener) }
            finish(null)
        }, TIMEOUT_MS)
    }
}

internal fun Long.toLifeIsoTimestamp(): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US
).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(this))
