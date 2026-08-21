package com.nanocomm.nanosmart.eventos

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ServiceMode {
    SELF_MONITORING,
    MONITORING;

    companion object {
        fun fromStored(value: String?): ServiceMode = when (value?.trim()?.uppercase()) {
            SELF_MONITORING.name -> SELF_MONITORING
            MONITORING.name -> MONITORING
            else -> MONITORING
        }
    }
}

data class PanelConfig(
    val panelName: String,
    val serviceMode: ServiceMode,
    val clave: String,
    val id: String,
    val imei: String,
    val abonado: String,
    val ip: String,
    val port: Int,
    val accessToken: String
)

object Prefs {
    private const val FILE = "nanosmart_eventos_prefs"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_NAME = "installation_name"
    private const val KEY_PANELS = "panels_json"
    private const val KEY_SELECTED_IMEI = "selected_panel_imei"

    // Claves antiguas: se conservan para migrar automáticamente la instalación actual.
    private const val KEY_CLAVE = "clave"
    private const val KEY_ID = "id"
    private const val KEY_IMEI = "imei"
    private const val KEY_ABONADO = "abonado"
    private const val KEY_IP = "ip"
    private const val KEY_PORT = "port"
    private const val KEY_ACCESS_TOKEN = "server_access_token"

    private const val KEY_STATUS = "status"
    private const val KEY_LAST_ACTOR = "last_action_actor"
    private const val KEY_LAST_ACTION_SOURCE = "last_action_source"
    private const val KEY_LAST_ALERT_ID = "last_alert_id"
    private const val KEY_COUNTER = "counter"

    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_FCM_SYNCED_TOKEN = "fcm_synced_token"
    private const val KEY_FCM_SYNCED_ACCESS_TOKENS = "fcm_synced_access_tokens"
    private const val KEY_ACTIVE_AUDIBLE_ALARM = "active_audible_alarm"
    private const val KEY_ACTIVE_ALARM_TITLE = "active_alarm_title"
    private const val KEY_ACTIVE_ALARM_BODY = "active_alarm_body"
    private const val KEY_ACTIVE_ALARM_ID = "active_alarm_id"
    private const val KEY_ACTIVE_ALARM_IMEI = "active_alarm_imei"
    private const val KEY_ACTIVE_ALARM_LATITUDE = "active_alarm_latitude"
    private const val KEY_ACTIVE_ALARM_LONGITUDE = "active_alarm_longitude"

    private val imeiPattern = Regex("\\d{15}")

    private data class PanelStore(
        val raw: String,
        val panels: List<PanelConfig>,
        val byImei: Map<String, PanelConfig>
    )

    @Volatile
    private var cachedPanelStore: PanelStore? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun panels(ctx: Context): List<PanelConfig> = loadPanelStore(ctx).panels

    private fun loadPanelStore(ctx: Context): PanelStore {
        migrateLegacyPanel(ctx)
        val raw = prefs(ctx).getString(KEY_PANELS, "").orEmpty()
        cachedPanelStore?.takeIf { it.raw == raw }?.let { return it }

        return synchronized(this) {
            cachedPanelStore?.takeIf { it.raw == raw }?.let { return@synchronized it }
            val decoded = decodePanels(raw)
            PanelStore(raw, decoded, decoded.associateBy { it.imei }).also {
                cachedPanelStore = it
            }
        }
    }

    private fun decodePanels(raw: String): List<PanelConfig> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val imei = item.optString("imei").trim()
                    if (!imeiPattern.matches(imei)) continue
                    add(
                        PanelConfig(
                            panelName = item.optString("panelName").trim()
                                .ifEmpty { defaultPanelName(item.optString("abonado"), imei) },
                            serviceMode = ServiceMode.fromStored(item.optString("serviceMode")),
                            clave = item.optString("clave").trim(),
                            id = item.optString("id").trim(),
                            imei = imei,
                            abonado = item.optString("abonado").trim(),
                            ip = item.optString("ip").trim(),
                            port = item.optInt("port", 0),
                            accessToken = item.optString("accessToken").trim()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun selectedPanel(ctx: Context): PanelConfig? {
        val store = loadPanelStore(ctx)
        if (store.panels.isEmpty()) return null
        val selectedImei = prefs(ctx).getString(KEY_SELECTED_IMEI, "").orEmpty()
        return store.byImei[selectedImei] ?: store.panels.first().also {
            prefs(ctx).edit().putString(KEY_SELECTED_IMEI, it.imei).apply()
        }
    }

    fun panelByImei(ctx: Context, imei: String?): PanelConfig? {
        val normalized = imei?.trim().orEmpty()
        return loadPanelStore(ctx).byImei[normalized]
    }

    fun selectPanel(ctx: Context, imei: String): Boolean {
        val panel = panelByImei(ctx, imei) ?: return false
        prefs(ctx).edit()
            .putString(KEY_SELECTED_IMEI, panel.imei)
            .putLegacyPanel(panel)
            .apply()
        return true
    }

    fun savePanelConfig(
        ctx: Context,
        panelName: String,
        actorName: String,
        serviceMode: ServiceMode,
        clave: String,
        id: String,
        imei: String,
        abonado: String,
        ip: String,
        port: Int,
        accessToken: String,
        originalImei: String? = null
    ) {
        val normalizedImei = imei.trim()
        val panel = PanelConfig(
            panelName = panelName.trim(),
            serviceMode = serviceMode,
            clave = clave.trim(),
            id = id.trim(),
            imei = normalizedImei,
            abonado = abonado.trim(),
            ip = ip.trim(),
            port = port,
            accessToken = accessToken.trim()
        )
        val current = panels(ctx).toMutableList()
        val original = originalImei?.trim().orEmpty()
        val duplicate = current.any { it.imei == normalizedImei && it.imei != original }
        require(!duplicate) { "Este IMEI ya está cargado" }

        val index = current.indexOfFirst {
            (original.isNotEmpty() && it.imei == original) ||
                (original.isEmpty() && it.imei == normalizedImei)
        }
        if (index >= 0) current[index] = panel else current.add(panel)

        prefs(ctx).edit()
            .putString(KEY_PANELS, encodePanels(current))
            .putString(KEY_SELECTED_IMEI, normalizedImei)
            .putString(KEY_NAME, actorName.trim())
            .putBoolean(KEY_CONFIGURED, true)
            .putLegacyPanel(panel)
            .apply()
        cachedPanelStore = null
    }

    fun replacePanelsForDemo(
        ctx: Context,
        actorName: String,
        panels: List<PanelConfig>
    ) {
        check(DemoMode.enabled) { "Los datos de demostración sólo están disponibles en la app Demo" }
        require(panels.isNotEmpty())
        val first = panels.first()
        prefs(ctx).edit()
            .putString(KEY_PANELS, encodePanels(panels))
            .putString(KEY_SELECTED_IMEI, first.imei)
            .putString(KEY_NAME, actorName.trim())
            .putBoolean(KEY_CONFIGURED, true)
            .putLegacyPanel(first)
            .apply()
        cachedPanelStore = null
    }

    fun isConfigured(ctx: Context): Boolean =
        panels(ctx).isNotEmpty() && name(ctx).isNotBlank()

    fun name(ctx: Context): String =
        prefs(ctx).getString(KEY_NAME, "").orEmpty()

    fun panelName(ctx: Context): String = selectedPanel(ctx)?.panelName.orEmpty()
    fun serviceMode(ctx: Context): ServiceMode =
        selectedPanel(ctx)?.serviceMode ?: ServiceMode.MONITORING
    fun clave(ctx: Context): String = selectedPanel(ctx)?.clave.orEmpty()
    fun id(ctx: Context): String = selectedPanel(ctx)?.id.orEmpty()
    fun imei(ctx: Context): String = selectedPanel(ctx)?.imei.orEmpty()
    fun abonado(ctx: Context): String = selectedPanel(ctx)?.abonado.orEmpty()
    fun ip(ctx: Context): String = selectedPanel(ctx)?.ip.orEmpty()
    fun port(ctx: Context): Int = selectedPanel(ctx)?.port ?: 0
    fun token(ctx: Context): String = selectedPanel(ctx)?.accessToken.orEmpty()

    fun status(ctx: Context): String = statusForImei(ctx, imei(ctx))

    fun statusForImei(ctx: Context, imei: String): String =
        prefs(ctx).getString(scoped(KEY_STATUS, imei), "DESARMADO") ?: "DESARMADO"

    fun setStatus(ctx: Context, status: String) = setStatusForImei(ctx, imei(ctx), status)

    fun setStatusForImei(ctx: Context, imei: String, status: String) {
        if (imei.isBlank()) return
        prefs(ctx).edit().putString(scoped(KEY_STATUS, imei), status).apply()
    }

    fun setLastActionActor(ctx: Context, actorName: String?, actionSource: String?) =
        setLastActionActorForImei(ctx, imei(ctx), actorName, actionSource)

    fun setLastActionActorForImei(
        ctx: Context,
        imei: String,
        actorName: String?,
        actionSource: String?
    ) {
        if (imei.isBlank()) return
        prefs(ctx).edit()
            .putString(scoped(KEY_LAST_ACTOR, imei), actorName.orEmpty())
            .putString(scoped(KEY_LAST_ACTION_SOURCE, imei), actionSource.orEmpty())
            .apply()
    }

    fun lastActionActor(ctx: Context): String =
        prefs(ctx).getString(scoped(KEY_LAST_ACTOR, imei(ctx)), "").orEmpty()

    fun lastActionSource(ctx: Context): String =
        prefs(ctx).getString(scoped(KEY_LAST_ACTION_SOURCE, imei(ctx)), "").orEmpty()

    fun fcmToken(ctx: Context): String =
        prefs(ctx).getString(KEY_FCM_TOKEN, "").orEmpty()

    fun setFcmToken(ctx: Context, value: String) {
        val storage = prefs(ctx)
        val editor = storage.edit().putString(KEY_FCM_TOKEN, value)
        if (storage.getString(KEY_FCM_TOKEN, "") != value) {
            editor.remove(KEY_FCM_SYNCED_TOKEN).remove(KEY_FCM_SYNCED_ACCESS_TOKENS)
        }
        editor.apply()
    }

    fun isFcmTokenSynced(ctx: Context, pushToken: String, accessToken: String): Boolean {
        return fcmSyncedAccessTokens(ctx, pushToken).contains(accessToken)
    }

    fun markFcmTokenSynced(ctx: Context, pushToken: String, accessToken: String) {
        markFcmTokensSynced(ctx, pushToken, listOf(accessToken))
    }

    fun fcmSyncedAccessTokens(ctx: Context, pushToken: String): Set<String> {
        val storage = prefs(ctx)
        if (storage.getString(KEY_FCM_SYNCED_TOKEN, "") != pushToken) return emptySet()
        return readStringSet(storage.getString(KEY_FCM_SYNCED_ACCESS_TOKENS, "").orEmpty())
    }

    @Synchronized
    fun markFcmTokensSynced(ctx: Context, pushToken: String, accessTokens: Collection<String>) {
        if (accessTokens.isEmpty()) return
        val storage = prefs(ctx)
        val synced = fcmSyncedAccessTokens(ctx, pushToken).toMutableSet()
        synced.addAll(accessTokens)
        storage.edit()
            .putString(KEY_FCM_SYNCED_TOKEN, pushToken)
            .putString(KEY_FCM_SYNCED_ACCESS_TOKENS, JSONArray(synced.toList()).toString())
            .apply()
    }

    fun lastAlertId(ctx: Context): Long =
        prefs(ctx).getLong(scoped(KEY_LAST_ALERT_ID, imei(ctx)), 0L)

    fun setLastAlertId(ctx: Context, id: Long) {
        prefs(ctx).edit().putLong(scoped(KEY_LAST_ALERT_ID, imei(ctx)), id).apply()
    }

    fun nextCounterForSend(ctx: Context): Int {
        val key = scoped(KEY_COUNTER, imei(ctx))
        val current = prefs(ctx).getInt(key, 60)
        val next = if (current >= 90) 60 else current + 1
        prefs(ctx).edit().putInt(key, next).apply()
        return current
    }

    fun resetCounter(ctx: Context) {
        prefs(ctx).edit().putInt(scoped(KEY_COUNTER, imei(ctx)), 60).apply()
    }

    fun setActiveAudibleAlarm(
        ctx: Context,
        title: String,
        body: String,
        alertId: Long,
        imei: String,
        latitude: Double?,
        longitude: Double?
    ) {
        val editor = prefs(ctx).edit()
            .putBoolean(KEY_ACTIVE_AUDIBLE_ALARM, true)
            .putString(KEY_ACTIVE_ALARM_TITLE, title)
            .putString(KEY_ACTIVE_ALARM_BODY, body)
            .putLong(KEY_ACTIVE_ALARM_ID, alertId)
            .putString(KEY_ACTIVE_ALARM_IMEI, imei)
        if (latitude != null && longitude != null) {
            editor
                .putString(KEY_ACTIVE_ALARM_LATITUDE, latitude.toString())
                .putString(KEY_ACTIVE_ALARM_LONGITUDE, longitude.toString())
        } else {
            editor
                .remove(KEY_ACTIVE_ALARM_LATITUDE)
                .remove(KEY_ACTIVE_ALARM_LONGITUDE)
        }
        editor.apply()
    }

    fun activeAudibleAlarm(ctx: Context): ActiveAudibleAlarm? {
        if (!prefs(ctx).getBoolean(KEY_ACTIVE_AUDIBLE_ALARM, false)) return null
        return ActiveAudibleAlarm(
            title = prefs(ctx).getString(KEY_ACTIVE_ALARM_TITLE, "Alerta NanoSmart")
                ?: "Alerta NanoSmart",
            body = prefs(ctx).getString(
                KEY_ACTIVE_ALARM_BODY,
                "Abrí la app para detener la alarma"
            ) ?: "Abrí la app para detener la alarma",
            alertId = prefs(ctx).getLong(KEY_ACTIVE_ALARM_ID, System.currentTimeMillis()),
            imei = prefs(ctx).getString(KEY_ACTIVE_ALARM_IMEI, "").orEmpty(),
            latitude = prefs(ctx).getString(KEY_ACTIVE_ALARM_LATITUDE, null)?.toDoubleOrNull(),
            longitude = prefs(ctx).getString(KEY_ACTIVE_ALARM_LONGITUDE, null)?.toDoubleOrNull()
        )
    }

    fun clearActiveAudibleAlarm(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_ACTIVE_AUDIBLE_ALARM, false)
            .remove(KEY_ACTIVE_ALARM_TITLE)
            .remove(KEY_ACTIVE_ALARM_BODY)
            .remove(KEY_ACTIVE_ALARM_ID)
            .remove(KEY_ACTIVE_ALARM_IMEI)
            .remove(KEY_ACTIVE_ALARM_LATITUDE)
            .remove(KEY_ACTIVE_ALARM_LONGITUDE)
            .apply()
    }

    private fun migrateLegacyPanel(ctx: Context) {
        val storage = prefs(ctx)
        if (!storage.getString(KEY_PANELS, "").isNullOrBlank()) return
        if (!storage.getBoolean(KEY_CONFIGURED, false)) return
        val imei = storage.getString(KEY_IMEI, "").orEmpty().trim()
        if (!imeiPattern.matches(imei)) return
        val abonado = storage.getString(KEY_ABONADO, "").orEmpty().trim()
        val panel = PanelConfig(
            panelName = defaultPanelName(abonado, imei),
            serviceMode = ServiceMode.MONITORING,
            clave = storage.getString(KEY_CLAVE, "").orEmpty(),
            id = storage.getString(KEY_ID, "").orEmpty(),
            imei = imei,
            abonado = abonado,
            ip = storage.getString(KEY_IP, "").orEmpty(),
            port = storage.getInt(KEY_PORT, 0),
            accessToken = storage.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        )
        storage.edit()
            .putString(KEY_PANELS, encodePanels(listOf(panel)))
            .putString(KEY_SELECTED_IMEI, imei)
            .putString(
                scoped(KEY_STATUS, imei),
                storage.getString(KEY_STATUS, "DESARMADO") ?: "DESARMADO"
            )
            .putString(
                scoped(KEY_LAST_ACTOR, imei),
                storage.getString(KEY_LAST_ACTOR, "").orEmpty()
            )
            .putString(
                scoped(KEY_LAST_ACTION_SOURCE, imei),
                storage.getString(KEY_LAST_ACTION_SOURCE, "").orEmpty()
            )
            .putLong(scoped(KEY_LAST_ALERT_ID, imei), storage.getLong(KEY_LAST_ALERT_ID, 0L))
            .putInt(scoped(KEY_COUNTER, imei), storage.getInt(KEY_COUNTER, 60))
            .apply()
        cachedPanelStore = null
    }

    private fun encodePanels(items: List<PanelConfig>): String = JSONArray().apply {
        items.forEach { panel ->
            put(
                JSONObject()
                    .put("panelName", panel.panelName)
                    .put("serviceMode", panel.serviceMode.name)
                    .put("clave", panel.clave)
                    .put("id", panel.id)
                    .put("imei", panel.imei)
                    .put("abonado", panel.abonado)
                    .put("ip", panel.ip)
                    .put("port", panel.port)
                    .put("accessToken", panel.accessToken)
            )
        }
    }.toString()

    private fun readStringSet(raw: String): MutableSet<String> = runCatching {
        val array = JSONArray(raw)
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.toMutableSet()
    }.getOrDefault(mutableSetOf())

    private fun SharedPreferences.Editor.putLegacyPanel(panel: PanelConfig): SharedPreferences.Editor =
        putString(KEY_CLAVE, panel.clave)
            .putString(KEY_ID, panel.id)
            .putString(KEY_IMEI, panel.imei)
            .putString(KEY_ABONADO, panel.abonado)
            .putString(KEY_IP, panel.ip)
            .putInt(KEY_PORT, panel.port)
            .putString(KEY_ACCESS_TOKEN, panel.accessToken)

    private fun scoped(base: String, imei: String): String = "${base}_${imei.ifBlank { "none" }}"

    private fun defaultPanelName(abonado: String, imei: String): String =
        abonado.trim().takeIf { it.isNotEmpty() }?.let { "Panel $it" }
            ?: "Panel ${imei.takeLast(4)}"
}

data class ActiveAudibleAlarm(
    val title: String,
    val body: String,
    val alertId: Long,
    val imei: String,
    val latitude: Double?,
    val longitude: Double?
)
