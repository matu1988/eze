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
import java.util.UUID

data class LifeConfig(
    val enabled: Boolean,
    val personName: String,
    val panelName: String,
    val imei: String,
    val token: String,
    val abonado: String,
    val transmitterId: String,
    val key: String,
    val monitoringIp: String,
    val monitoringPort: Int,
    val deviceAddress: String,
    val deviceName: String
) {
    fun validForService(): Boolean = enabled && imei.matches(Regex("\\d{15}")) &&
        token.isNotBlank() && abonado.isNotBlank() && transmitterId.isNotBlank() &&
        key.isNotBlank() && monitoringIp.isNotBlank() && monitoringPort in 1..65535 &&
        deviceAddress.isNotBlank()
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
    val buttonBattery: Int?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("createdAtMillis", createdAtMillis)
        .put("udpPayload", udpPayload)
        .put("buttonId", buttonId)
        .put("buttonBattery", buttonBattery ?: JSONObject.NULL)
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
                    latitude = json.getDouble("latitude"),
                    longitude = json.getDouble("longitude"),
                    accuracyMeters = if (json.isNull("accuracy")) null else json.getDouble("accuracy").toFloat(),
                    capturedAtMillis = json.optLong("capturedAtMillis", json.getLong("createdAtMillis"))
                )
            } else null
            PendingLifeEvent(
                requestId = json.getString("requestId"),
                createdAtMillis = json.getLong("createdAtMillis"),
                udpPayload = json.getString("udpPayload"),
                location = location,
                buttonId = json.optString("buttonId"),
                buttonBattery = if (json.isNull("buttonBattery")) null else json.optInt("buttonBattery")
            )
        }.getOrNull()
    }
}

object LifePrefs {
    private const val FILE = "nanosmart_vida"
    private const val QUEUE = "pending_events"
    private fun p(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): LifeConfig = LifeConfig(
        enabled = p(context).getBoolean("enabled", false),
        personName = p(context).getString("personName", "").orEmpty(),
        panelName = p(context).getString("panelName", "").orEmpty(),
        imei = p(context).getString("imei", "").orEmpty(),
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

    @Synchronized
    fun nextCounter(context: Context): Int {
        val current = p(context).getInt("counter", 60)
        p(context).edit().putInt("counter", if (current >= 90) 60 else current + 1).apply()
        return current
    }

    fun setConnection(context: Context, connected: Boolean) =
        p(context).edit().putBoolean("connected", connected).putLong("connectionUpdated", System.currentTimeMillis()).apply()
    fun connected(context: Context) = p(context).getBoolean("connected", false)
    fun setBattery(context: Context, value: Int?) = p(context).edit().apply {
        if (value == null) remove("battery") else putInt("battery", value.coerceIn(0, 100))
    }.apply()
    fun battery(context: Context): Int? = if (p(context).contains("battery")) p(context).getInt("battery", 0) else null
    fun setLastPress(context: Context, value: Long) = p(context).edit().putLong("lastPress", value).apply()
    fun lastPress(context: Context) = p(context).getLong("lastPress", 0L)
    fun setServerState(context: Context, value: String) = p(context).edit().putString("serverState", value).apply()
    fun serverState(context: Context) = p(context).getString("serverState", "Sin comprobar").orEmpty()

    @Synchronized
    fun queue(context: Context): MutableList<PendingLifeEvent> {
        val raw = p(context).getString(QUEUE, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> PendingLifeEvent.fromJson(array.getJSONObject(index))!! }
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

object LifeLocationProvider {
    // No se reutiliza una ubicación antigua: sólo se acepta una lectura muy reciente del teléfono.
    private const val MAX_AGE_MS = 60_000L

    @SuppressLint("MissingPermission")
    fun bestLastKnown(context: Context): LifeLocation? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { valid(it) && now - it.time.coerceAtMost(now) <= MAX_AGE_MS }
            .maxByOrNull { it.time }
            ?.let {
                LifeLocation(it.latitude, it.longitude, it.accuracy.takeIf { _ -> it.hasAccuracy() }, it.time)
            }
    }

    private fun valid(location: Location) = location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
}

object LifePacketBuilder {
    const val CONTACT_ID_BLOCK = "181640010000"

    fun build(context: Context, config: LifeConfig): String {
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
    private const val XOR_KEY = 0x5A
    private val encodedHost = intArrayOf(111,110,116,104,105,104,116,107,107,111,116,107,106,108)
    private val encodedPort = intArrayOf(107,98,106,98,104)
    private fun reveal(values: IntArray) = values.joinToString("") { (it xor XOR_KEY).toChar().toString() }
    private val baseUrl get() = "http://${reveal(encodedHost)}:${reveal(encodedPort)}"

    fun newEvent(context: Context, config: LifeConfig, battery: Int?): PendingLifeEvent {
        val now = System.currentTimeMillis()
        return PendingLifeEvent(
            requestId = "vida-${UUID.randomUUID()}",
            createdAtMillis = now,
            udpPayload = LifePacketBuilder.build(context, config),
            location = LifeLocationProvider.bestLastKnown(context),
            buttonId = config.deviceAddress,
            buttonBattery = battery
        )
    }

    fun send(config: LifeConfig, event: PendingLifeEvent) {
        var udpError: Throwable? = null
        var httpError: Throwable? = null
        runCatching { sendUdp(config, event.udpPayload) }.onFailure { udpError = it }
        runCatching { sendHttp(config, event) }.onFailure { httpError = it }
        if (udpError != null || httpError != null) {
            throw IOException(
                listOfNotNull(
                    udpError?.message?.let { "monitoreo UDP: $it" },
                    httpError?.message?.let { "NanoSmart Server: $it" }
                ).joinToString(" | ")
            )
        }
    }

    private fun sendUdp(config: LifeConfig, payload: String) {
        val data = payload.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            socket.soTimeout = 5000
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(config.monitoringIp), config.monitoringPort))
        }
    }

    private fun sendHttp(config: LifeConfig, event: PendingLifeEvent) {
        val body = JSONObject()
            .put("type", "VIDA")
            .put("name", config.personName)
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
        val connection = (URL("$baseUrl/api/app/emergency").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("NanoSmart Server HTTP $status ${detail.take(180)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun iso(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(Date(millis))
}
