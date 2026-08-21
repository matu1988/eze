package com.nanocomm.nanosmart.eventos

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ServerAlert(
    val id: Long,
    val receivedAt: String,
    val eventCode: String,
    val eventDescription: String,
    val partition: String?,
    val subject: String?,
    val subjectNumber: Int?,
    val subjectKind: String?,
    val zoneName: String?,
    val abonado: String?,
    val imei: String,
    val actionSource: String?,
    val actorName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Double?,
    val locationCapturedAt: String?
)

data class AlertsResponse(
    val imei: String,
    val alerts: List<ServerAlert>
)

data class DeviceCommand(
    val id: String,
    val imei: String,
    val action: String,
    val status: String,
    val requestedAt: String,
    val actionSentAt: String?,
    val confirmedAt: String?,
    val deliveredAt: String?,
    val resultCode: String?,
    val result: String?,
    val panelStatus: String?,
    val alreadyInState: Boolean,
    val resultDescription: String?,
    val actionSource: String?,
    val actorName: String?,
    val error: String?
)

data class DevicePanelState(
    val panelStatus: String,
    val result: String?,
    val resultCode: String?,
    val alreadyInState: Boolean,
    val resultDescription: String?,
    val confirmedAt: String?,
    val actionSource: String?,
    val actorName: String?
)

data class DeviceStatusResponse(
    val latestCommand: DeviceCommand?,
    val panelState: DevicePanelState?
)

data class RegisteredInstallation(
    val imei: String,
    val accessToken: String
)

object AlertApiClient {
    fun registerInstallation(imei: String, accessKey: String, name: String): RegisteredInstallation {
        val request = JSONObject()
            .put("imei", imei)
            .put("accessKey", accessKey)
            .put("name", name)
            .put("platform", "ANDROID")
        val root = requestJson(ServerConfig.registrationUrl, "POST", "", request.toString())
        val registeredImei = root.optString("imei").trim()
        val accessToken = root.optString("accessToken").trim()
        if (!registeredImei.matches(Regex("\\d{15}")) || accessToken.isEmpty()) {
            throw IOException("El receptor no devolvió una vinculación válida")
        }
        return RegisteredInstallation(registeredImei, accessToken)
    }

    fun fetchZoneNames(accessToken: String): Map<Int, String> {
        val root = requestJson(ServerConfig.zoneNamesUrl, "GET", accessToken)
        return parseZoneNames(root)
    }

    fun saveZoneNames(accessToken: String, zoneNames: Map<Int, String>): Map<Int, String> {
        val zones = JSONObject()
        for (zone in 1..16) {
            zones.put(zone.toString(), zoneNames[zone].orEmpty().trim())
        }
        val request = JSONObject().put("zones", zones)
        val root = requestJson(ServerConfig.zoneNamesUrl, "PUT", accessToken, request.toString())
        return parseZoneNames(root)
    }

    fun sendDeviceCommand(accessToken: String, action: String, name: String): DeviceCommand {
        val request = JSONObject()
            .put("action", action)
            .put("name", name)
        val root = requestJson(ServerConfig.deviceCommandUrl, "POST", accessToken, request.toString())
        return parseCommand(root.getJSONObject("command"))
    }

    fun sendEmergency(
        accessToken: String,
        type: String,
        name: String,
        abonado: String,
        location: EmergencyLocation?
    ): ServerAlert {
        val request = JSONObject()
            .put("type", type)
            .put("name", name)
            .put("abonado", abonado)
        if (location != null) {
            request
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("locationCapturedAt", location.capturedAtMillis.toIsoTimestamp())
            location.accuracyMeters?.let { request.put("locationAccuracyMeters", it.toDouble()) }
        }
        val root = requestJson(ServerConfig.emergencyUrl, "POST", accessToken, request.toString())
        return parseAlert(root.getJSONObject("alert"), "")
    }

    fun updateInstallationName(accessToken: String, name: String) {
        val request = JSONObject().put("name", name)
        requestJson(ServerConfig.installationNameUrl, "POST", accessToken, request.toString())
    }

    fun fetchDeviceCommand(accessToken: String, commandId: String): DeviceCommand {
        val root = requestJson(ServerConfig.deviceCommandStatusUrl(commandId), "GET", accessToken)
        return parseCommand(root.getJSONObject("command"))
    }

    fun fetchDeviceStatus(accessToken: String): DeviceStatusResponse {
        val root = requestJson(ServerConfig.deviceStatusUrl, "GET", accessToken)
        return DeviceStatusResponse(
            latestCommand = root.optJSONObject("latestCommand")?.let(::parseCommand),
            panelState = root.optJSONObject("panelState")?.let(::parsePanelState)
        )
    }

    fun fetchAlerts(accessToken: String, expectedImei: String, afterId: Long?): AlertsResponse {
        check(!DemoMode.enabled) { "La versión Demo no realiza conexiones externas" }
        val connection = (URL(ServerConfig.alertsUrl(afterId)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            if (accessToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val serverMessage = runCatching {
                    JSONObject(body).optString("error").trim()
                }.getOrDefault("")
                val detail = serverMessage.ifEmpty { "respuesta HTTP $status" }
                throw IOException("El servidor rechazó la consulta: $detail")
            }

            val root = JSONObject(body)
            val responseImei = root.optString("imei").trim()
            if (responseImei.isEmpty()) {
                throw IOException("La respuesta del servidor no contiene un IMEI")
            }
            if (expectedImei.isNotEmpty() && responseImei != expectedImei) {
                throw IOException("El token pertenece al IMEI $responseImei y no al $expectedImei")
            }

            val array = root.optJSONArray("alerts")
                ?: throw IOException("La respuesta del servidor no contiene alertas")

            val alerts = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(parseAlert(item, responseImei))
                }
            }

            return AlertsResponse(responseImei, alerts)
        } finally {
            connection.disconnect()
        }
    }

    private fun text(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun number(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun Long.toIsoTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(this))

    private fun parseZoneNames(root: JSONObject): Map<Int, String> {
        val zones = root.optJSONObject("zones")
            ?: throw IOException("La respuesta del servidor no contiene las zonas")
        return (1..16).associateWith { zone ->
            zones.optString(zone.toString()).trim()
        }
    }

    private fun parseCommand(json: JSONObject): DeviceCommand = DeviceCommand(
        id = json.optString("id").trim(),
        imei = json.optString("imei").trim(),
        action = json.optString("action").trim().uppercase(),
        status = json.optString("status").trim().uppercase(),
        requestedAt = json.optString("requestedAt").trim(),
        actionSentAt = text(json, "actionSentAt"),
        confirmedAt = text(json, "confirmedAt"),
        deliveredAt = text(json, "deliveredAt"),
        resultCode = text(json, "resultCode"),
        result = text(json, "result"),
        panelStatus = text(json, "panelStatus")?.uppercase(),
        alreadyInState = json.optBoolean("alreadyInState", false),
        resultDescription = text(json, "resultDescription"),
        actionSource = text(json, "actionSource")?.uppercase(),
        actorName = text(json, "actorName"),
        error = text(json, "error")
    )

    private fun parsePanelState(json: JSONObject): DevicePanelState = DevicePanelState(
        panelStatus = json.optString("panelStatus").trim().uppercase(),
        result = text(json, "result"),
        resultCode = text(json, "resultCode"),
        alreadyInState = json.optBoolean("alreadyInState", false),
        resultDescription = text(json, "resultDescription"),
        confirmedAt = text(json, "confirmedAt"),
        actionSource = text(json, "actionSource")?.uppercase(),
        actorName = text(json, "actorName")
    )

    private fun parseAlert(json: JSONObject, fallbackImei: String): ServerAlert = ServerAlert(
        id = json.optLong("id", 0L),
        receivedAt = text(json, "receivedAt").orEmpty(),
        eventCode = text(json, "eventCode") ?: "—",
        eventDescription = text(json, "eventDescription") ?: "Evento de alarma",
        partition = text(json, "partition"),
        subject = text(json, "subject"),
        subjectNumber = if (json.has("subjectNumber") && !json.isNull("subjectNumber")) {
            json.optInt("subjectNumber")
        } else {
            null
        },
        subjectKind = text(json, "subjectKind"),
        zoneName = text(json, "zoneName"),
        abonado = text(json, "abonado"),
        imei = text(json, "imei") ?: fallbackImei,
        actionSource = text(json, "actionSource")?.uppercase(),
        actorName = text(json, "actorName"),
        latitude = number(json, "latitude"),
        longitude = number(json, "longitude"),
        locationAccuracyMeters = number(json, "locationAccuracyMeters"),
        locationCapturedAt = text(json, "locationCapturedAt")
    )

    private fun requestJson(
        url: String,
        method: String,
        accessToken: String,
        requestBody: String? = null
    ): JSONObject {
        check(!DemoMode.enabled) { "La versión Demo no realiza conexiones externas" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            if (requestBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (requestBody != null) {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val serverMessage = runCatching { JSONObject(body).optString("error").trim() }
                    .getOrDefault("")
                throw IOException(serverMessage.ifEmpty { "El servidor respondió HTTP $status" })
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
