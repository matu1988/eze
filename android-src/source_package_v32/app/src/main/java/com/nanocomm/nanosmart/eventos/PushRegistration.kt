package com.nanocomm.nanosmart.eventos

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PushRegistration {
    private const val TAG = "NanoSmartFCM"

    fun syncCurrentToken(context: Context, force: Boolean = false) {
        if (DemoMode.enabled) return
        val appContext = context.applicationContext
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { pushToken ->
                syncToken(appContext, pushToken, force)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "No se pudo obtener el token FCM", error)
            }
    }

    fun syncCurrentTokenForPanel(
        context: Context,
        accessToken: String,
        force: Boolean = true
    ) {
        if (DemoMode.enabled || accessToken.isBlank()) return
        val appContext = context.applicationContext
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { pushToken ->
                syncTokenForAccessTokens(appContext, pushToken, listOf(accessToken), force)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "No se pudo obtener el token FCM", error)
            }
    }

    fun syncToken(context: Context, pushToken: String, force: Boolean = false) {
        if (DemoMode.enabled) return
        val appContext = context.applicationContext
        val normalizedPushToken = pushToken.trim()
        if (normalizedPushToken.isEmpty()) return

        val accessTokens = Prefs.panels(appContext)
            .map { it.accessToken.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        syncTokenForAccessTokens(appContext, normalizedPushToken, accessTokens, force)
    }

    private fun syncTokenForAccessTokens(
        context: Context,
        pushToken: String,
        accessTokens: List<String>,
        force: Boolean
    ) {
        val normalizedPushToken = pushToken.trim()
        if (normalizedPushToken.isEmpty() || accessTokens.isEmpty()) return
        Prefs.setFcmToken(context, normalizedPushToken)
        val alreadySynced = if (force) emptySet() else {
            Prefs.fcmSyncedAccessTokens(context, normalizedPushToken)
        }
        val pending = accessTokens
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && (force || it !in alreadySynced) }
            .distinct()
            .toList()
        if (pending.isEmpty()) return

        Thread {
            val completed = mutableListOf<String>()
            pending.forEach { accessToken ->
                if (syncAccessToken(normalizedPushToken, accessToken)) {
                    completed.add(accessToken)
                    if (completed.size >= SYNC_CHECKPOINT_SIZE) {
                        Prefs.markFcmTokensSynced(context, normalizedPushToken, completed)
                        completed.clear()
                    }
                }
            }
            Prefs.markFcmTokensSynced(context, normalizedPushToken, completed)
        }.start()
    }

    private fun syncAccessToken(pushToken: String, accessToken: String): Boolean {
        val connection = (URL(ServerConfig.pushTokenUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

        try {
            val body = JSONObject()
                .put("pushToken", pushToken)
                .put("platform", "ANDROID")
                .toString()
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status in 200..299) {
                Log.i(TAG, "Token FCM registrado para un panel NanoSmart")
                return true
            } else {
                Log.w(TAG, "El servidor rechazó el token FCM: HTTP $status")
            }
        } catch (error: Exception) {
            Log.w(TAG, "No se pudo registrar el token FCM", error)
        } finally {
            connection.disconnect()
        }
        return false
    }

    private const val SYNC_CHECKPOINT_SIZE = 25
}
