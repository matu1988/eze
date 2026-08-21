package com.nanocomm.nanosmart.eventos

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun AlertApiClient.sendLifeEmergency(
    accessToken: String,
    name: String,
    panelName: String,
    abonado: String,
    requestId: String,
    buttonId: String,
    buttonBattery: Int?,
    location: EmergencyLocation?
) {
    val body = JSONObject()
        .put("type", "VIDA")
        .put("name", name)
        .put("panelName", panelName)
        .put("abonado", abonado)
        .put("requestId", requestId)
        .put("buttonId", buttonId)
        .put("buttonBattery", buttonBattery ?: JSONObject.NULL)
    if (location != null) {
        body.put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("locationCapturedAt", location.capturedAtMillis.toLifeIsoTimestamp())
        location.accuracyMeters?.let { body.put("locationAccuracyMeters", it.toDouble()) }
    }
    postLifeJson(ServerConfig.emergencyUrl, accessToken, body)
}

fun AlertApiClient.sendLifeStatus(
    accessToken: String,
    status: String,
    panelName: String,
    buttonId: String,
    buttonBattery: Int?,
    name: String
) {
    val body = JSONObject()
        .put("status", status)
        .put("panelName", panelName)
        .put("buttonId", buttonId)
        .put("buttonBattery", buttonBattery ?: JSONObject.NULL)
        .put("name", name)
    postLifeJson(ServerConfig.baseUrl + "/api/app/life/status", accessToken, body)
}

private fun postLifeJson(url: String, accessToken: String, body: JSONObject): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 7_000
        readTimeout = 9_000
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
    }
    try {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(response).optString("error") }.getOrDefault("")
            throw IOException(message.ifBlank { "NanoSmart Server respondió HTTP $status" })
        }
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    } finally {
        connection.disconnect()
    }
}
