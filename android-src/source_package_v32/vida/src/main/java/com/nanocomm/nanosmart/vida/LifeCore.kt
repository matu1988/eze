package com.nanocomm.nanosmart.vida

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class LifeConfig(
    val enabled: Boolean,
    val personName: String,
    val panelName: String,
    val imei: String,
    val accessKey: String,
    val token: String,
    val abonado: String,
    val transmitterId: String,
    val key: String,
    val monitoringIp: String,
    val monitoringPort: Int,
    val deviceAddress: String,
    val deviceName: String
) {
    fun validForRegistration(): Boolean =
        imei.matches(Regex("\\d{15}")) && accessKey.isNotBlank() && deviceAddress.isNotBlank()

    fun validForService(): Boolean = enabled && validForRegistration() && token.isNotBlank() &&
        personName.isNotBlank() && abonado.length == 4 && transmitterId.isNotBlank() &&
        key.isNotBlank() && monitoringIp.isNotBlank() && monitoringPort in 1..65535
}

data class LifeLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAtMillis: Long
)

data class PendingLifeEvent(
    val requestId: String,
    val createdAtMillis: Long,
    val udpPayload: String,
    val location: LifeLocation?,
    val buttonId: String,
    val buttonBattery: Int?,
    val udpSent: Boolean = false,
    val httpSent: Boolean = false
) {
    val complete: Boolean get() = udpSent && httpSent

    fun toJson(): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("createdAtMillis", createdAtMillis)
        .put("udpPayload", udpPayload)
        .put("buttonId", buttonId)
        .put("buttonBattery", buttonBattery ?: JSONObject.NULL)
        .put("udpSent", udpSent)
        .put("httpSent", httpSent)
        .apply {
            location?.let {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("accuracy", it.accuracyMeters?.toDouble() ?: JSONObject.NULL)
                put("capturedAtMillis", it.capturedAtMillis)
            }
        }

    companion object {
        fun fromJson(json: JSONObject): PendingLifeEvent? = runCatching {
            val location = if (json.has("latitude") && json.has("longitude")) {
                LifeLocation(
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                    if (json.isNull("accuracy")) null else json.getDouble("accuracy").toFloat(),
                    json.optLong("capturedAtMillis", json.getLong("createdAtMillis"))
                )
            } else null
            PendingLifeEvent(
                requestId = json.getString("requestId"),
                createdAtMillis = json.getLong("createdAtMillis"),
                udpPayload = json.getString("udpPayload"),
                location = location,
                buttonId = json.optString("buttonId"),
                buttonBattery = if (json.isNull("buttonBattery")) null else json.optInt("buttonBattery"),
                udpSent = json.optBoolean("udpSent", false),
                httpSent = json.optBoolean("httpSent", false)
            )
        }.getOrNull()
    }
}

data class SendAttempt(val event: PendingLifeEvent, val errors: List<String>)

object LifePrefs {
    private const val FILE = "nanosmart_vida"
    private const val QUEUE = "pending_events"
    private fun p(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): LifeConfig = LifeConfig(
        enabled = p(context).getBoolean("enabled", false),
        personName = p(context).getString("personName", "").orEmpty(),
        panelName = p(context).getString("panelName", "").orEmpty(),
        imei = p(context).getString("imei", "").orEmpty(),
        accessKey = p(context).getString("accessKey", "").orEmpty(),
        token = p(context).getString("token", "").orEmpty(),
        abonado = p(context).getString("abonado", "").orEmpty(),
        transmitterId = p(context).getString("transmitterId", "").orEmpty(),
        key = p(context).getString("key", "").orEmpty(),
        monitoringIp = p(context).getString("monitoringIp", "").orEmpty(),
        monitoringPort = p(context).getInt("monitoringPort", 0),
        deviceAddress = p(context).getString("deviceAddress", "").orEmpty(),
        deviceName = p(context).getString("deviceName", "").orEmpty()
    )

    fun save(context: Context, config: LifeConfig) {
        p(context).edit()
            .putBoolean("enabled", config.enabled)
            .putString("personName", config.personName.trim())
            .putString("panelName", config.panelName.trim())
            .putString("imei", config.imei.trim())
            .putString("accessKey", config.accessKey.trim().uppercase())
            .putString("token", config.token.trim())
            .putString("abonado", config.abonado.trim())
            .putString("transmitterId", config.transmitterId.trim())
            .putString("key", config.key.trim())
            .putString("monitoringIp", config.monitoringIp.trim())
            .putInt("monitoringPort", config.monitoringPort)
            .putString("deviceAddress", config.deviceAddress.trim())
            .putString("deviceName", config.deviceName.trim())
            .apply()
    }

    fun setToken(context: Context, token: String) = p(context).edit().putString("token", token).apply()

    @Synchronized
    fun nextCounter(context: Context): Int {
        val current = p(context).getInt("counter", 60)
        p(context).edit().putInt("counter", if (current >= 90) 60 else current + 1).apply()
        return current
    }

    fun setConnection(context: Context, connected: Boolean) {
        val editor = p(context).edit().putBoolean("connected", connected)
        if (!connected && p(context).getLong("disconnectedSince", 0L) == 0L) {
            editor.putLong("disconnectedSince", System.currentTimeMillis())
        }
        if (connected) editor.remove("disconnectedSince")
        editor.apply()
    }
    fun connected(context: Context) = p(context).getBoolean("connected", false)
    fun disconnectedSince(context: Context) = p(context).getLong("disconnectedSince", 0L)

    fun setBattery(context: Context, value: Int?) = p(context).edit().apply {
        if (value == null) remove("battery") else putInt("battery", value.coerceIn(0, 100))
    }.apply()
    fun battery(context: Context): Int? = if (p(context).contains("battery")) p(context).getInt("battery", 0) else null
    fun setLastPress(context: Context, value: Long) = p(context).edit().putLong("lastPress", value).apply()
    fun lastPress(context: Context) = p(context).getLong("lastPress", 0L)
    fun setServerState(context: Context, value: String) = p(context).edit().putString("serverState", value).apply()
    fun serverState(context: Context) = p(context).getString("serverState", "Sin comprobar").orEmpty()

    fun disconnectAlertSent(context: Context) = p(context).getBoolean("disconnectAlertSent", false)
    fun setDisconnectAlertSent(context: Context, value: Boolean) = p(context).edit().putBoolean("disconnectAlertSent", value).apply()
    fun batteryLowAlertSent(context: Context) = p(context).getBoolean("batteryLowAlertSent", false)
    fun setBatteryLowAlertSent(context: Context, value: Boolean) = p(context).edit().putBoolean("batteryLowAlertSent", value).apply()

    @Synchronized
    fun queue(context: Context): MutableList<PendingLifeEvent> {
        val raw = p(context).getString(QUEUE, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    PendingLifeEvent.fromJson(array.getJSONObject(i))?.let(::add)
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun replaceQueue(context: Context, events: List<PendingLifeEvent>) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        p(context).edit().putString(QUEUE, array.toString()).apply()
    }

    @Synchronized
    fun enqueue(context: Context, event: PendingLifeEvent) {
        val items = queue(context)
        if (items.none { it.requestId == event.requestId }) items.add(event)
        replaceQueue(context, items.takeLast(100))
    }
}

object NanoSmartServer {
    private const val XOR_KEY = 0x5A
    private val encodedHost = intArrayOf(111, 110, 116, 104, 105, 104, 116, 107, 107, 111, 116, 107, 106, 108)
    private val encodedPort = intArrayOf(107, 98, 106, 98, 104)
    private fun reveal(values: IntArray) = values.joinToString("") { (it xor XOR_KEY).toChar().toString() }
    val baseUrl: String get() = "http://${reveal(encodedHost)}:${reveal(encodedPort)}"
}

object LifeRegistration {
    fun register(config: LifeConfig): String {
        require(config.validForRegistration()) { "Faltan datos para registrar Botón Vida" }
        val body = JSONObject()
            .put("imei", config.imei)
            .put("accessKey", config.accessKey)
            .put("name", config.personName.ifBlank { "Botón Vida" })
            .put("platform", "ANDROID")
            .put("purpose", "LIFE_BUTTON")
            .put("deviceIdentifier", config.deviceAddress)
        val response = httpJson("${NanoSmartServer.baseUrl}/api/app/register", null, body)
        return response.optString("accessToken").takeIf { it.isNotBlank() }
            ?: throw IOException("El servidor no devolvió la credencial Botón Vida")
    }

    internal fun httpJson(url: String, token: String?, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 9000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("NanoSmart Server HTTP $status ${text.take(220)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

object LifeLocationProvider {
    private const val MAX_AGE_MS = 60_000L

    @SuppressLint("MissingPermission")
    fun bestRecent(context: Context): LifeLocation? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { valid(it) && it.time > 0L && now - it.time.coerceAtMost(now) <= MAX_AGE_MS }
            .maxByOrNull { it.time }
            ?.let { LifeLocation(it.latitude, it.longitude, it.accuracy.takeIf { _ -> it.hasAccuracy() }, it.time) }
    }

    private fun valid(location: Location) = location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
}

object LifePacketBuilder {
    const val CONTACT_ID_BLOCK = "181640010000"

    fun build(context: Context, config: LifeConfig): String {
        require(config.abonado.length == 4) { "El abonado debe tener 4 caracteres para Contact ID" }
        val date = SimpleDateFormat("dd/MM/yyyy-HH:mm", Locale.getDefault()).format(Date())
        val counter = String.format(Locale.US, "%02d", LifePrefs.nextCounter(context))
        return buildString {
            append("$B,")
            append(config.transmitterId)
            append(',')
            append(counter)
            append(',')
            append(date)
            append(",01,")
            append(config.abonado)
            append(CONTACT_ID_BLOCK)
            append(",18,0,0,")
            append(config.key)
            append(",15,MA_1.90GE-AR,0,0,0,0,0,0,0,")
            append(config.imei)
            append(",0,0,")
            append(config.monitoringIp)
            append(',')
            append(config.monitoringPort)
            append(",00,10,4G,$E")
        }
    }
}

object LifeSender {
    fun newEvent(context: Context, config: LifeConfig, battery: Int?): PendingLifeEvent = PendingLifeEvent(
        requestId = "vida-${UUID.randomUUID()}",
        createdAtMillis = System.currentTimeMillis(),
        udpPayload = LifePacketBuilder.build(context, config),
        location = LifeLocationProvider.bestRecent(context),
        buttonId = config.deviceAddress,
        buttonBattery = battery
    )

    fun attempt(config: LifeConfig, original: PendingLifeEvent): SendAttempt {
        var event = original
        val errors = mutableListOf<String>()
        if (!event.udpSent) {
            runCatching { sendUdp(config, event.udpPayload) }
                .onSuccess { event = event.copy(udpSent = true) }
                .onFailure { errors += "monitoreo UDP: ${it.message ?: it.javaClass.simpleName}" }
        }
        if (!event.httpSent) {
            runCatching { sendEmergencyHttp(config, event) }
                .onSuccess { event = event.copy(httpSent = true) }
                .onFailure { errors += "NanoSmart Server: ${it.message ?: it.javaClass.simpleName}" }
        }
        return SendAttempt(event, errors)
    }

    fun sendStatus(config: LifeConfig, status: String, battery: Int? = null) {
        val body = JSONObject()
            .put("status", status)
            .put("buttonId", config.deviceAddress)
            .put("buttonName", config.deviceName)
            .put("buttonBattery", battery ?: JSONObject.NULL)
            .put("name", config.personName)
            .put("panelName", config.panelName)
        LifeRegistration.httpJson("${NanoSmartServer.baseUrl}/api/app/life/status", config.token, body)
    }

    private fun sendUdp(config: LifeConfig, payload: String) {
        val data = payload.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            socket.soTimeout = 5000
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(config.monitoringIp), config.monitoringPort))
        }
    }

    private fun sendEmergencyHttp(config: LifeConfig, event: PendingLifeEvent) {
        val body = JSONObject()
            .put("type", "VIDA")
            .put("name", config.personName)
            .put("panelName", config.panelName)
            .put("abonado", config.abonado)
            .put("requestId", event.requestId)
            .put("buttonId", event.buttonId)
            .put("buttonBattery", event.buttonBattery ?: JSONObject.NULL)
        event.location?.let {
            body.put("latitude", it.latitude)
            body.put("longitude", it.longitude)
            body.put("locationCapturedAt", iso(it.capturedAtMillis))
            it.accuracyMeters?.let { accuracy -> body.put("locationAccuracyMeters", accuracy.toDouble()) }
        }
        LifeRegistration.httpJson("${NanoSmartServer.baseUrl}/api/app/emergency", config.token, body)
    }

    private fun iso(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))
}
