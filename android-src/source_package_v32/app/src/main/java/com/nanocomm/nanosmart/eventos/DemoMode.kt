package com.nanocomm.nanosmart.eventos

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Datos publicitarios aislados: nunca representan equipos o credenciales reales. */
object DemoMode {
    const val CASA_IMEI = "990000000001234"
    const val OFICINA_IMEI = "990000000005678"

    val enabled: Boolean
        get() = BuildConfig.DEMO_MODE

    fun prepare(context: Context) {
        if (!enabled) return
        Prefs.replacePanelsForDemo(
            context,
            actorName = "Familia NanoSmart",
            panels = listOf(
                PanelConfig(
                    panelName = "Casa",
                    serviceMode = ServiceMode.SELF_MONITORING,
                    clave = "1234",
                    id = "01",
                    imei = CASA_IMEI,
                    abonado = "2010",
                    ip = "",
                    port = 0,
                    accessToken = "TOKEN-DEMO-CASA"
                ),
                PanelConfig(
                    panelName = "Oficina Central",
                    serviceMode = ServiceMode.SELF_MONITORING,
                    clave = "5678",
                    id = "02",
                    imei = OFICINA_IMEI,
                    abonado = "6188",
                    ip = "",
                    port = 0,
                    accessToken = "TOKEN-DEMO-OFICINA"
                )
            )
        )
        Prefs.setStatusForImei(context, CASA_IMEI, "ARMADO")
        Prefs.setLastActionActorForImei(context, CASA_IMEI, "María", "APP")
        Prefs.setStatusForImei(context, OFICINA_IMEI, "DESARMADO")
        Prefs.setLastActionActorForImei(context, OFICINA_IMEI, "Carlos", "TECLADO")
        Prefs.selectPanel(context, CASA_IMEI)
    }

    fun alerts(imei: String = CASA_IMEI): List<ServerAlert> = listOf(
        alert(1010, 2, "45", "Armado GPRS", imei, actor = "María"),
        alert(
            1009, 8, "130", "Alarma de robo", imei,
            subject = "3", subjectNumber = 3, zoneName = "Puerta principal"
        ),
        alert(
            1008, 16, "110", "Pánico", imei, actor = "Carlos",
            latitude = -34.6037, longitude = -58.3816
        ),
        alert(1007, 28, "46", "Desarmado GPRS", imei, actor = "María"),
        alert(
            1006, 42, "131", "Alarma perimetral", imei,
            subject = "8", subjectNumber = 8, zoneName = "Patio trasero"
        ),
        alert(1005, 65, "100", "Emergencia médica", imei, actor = "Sofía"),
        alert(
            1004, 92, "132", "Alarma interior", imei,
            subject = "5", subjectNumber = 5, zoneName = "Living"
        ),
        alert(1003, 125, "120", "Incendio", imei, actor = "Carlos"),
        alert(1002, 180, "45", "Armado GPRS", imei, actor = "Carlos"),
        alert(1001, 245, "46", "Desarmado GPRS", imei, actor = "María")
    )

    fun emergencyAlert(type: String, imei: String, actor: String): ServerAlert {
        val (code, description) = when (type.uppercase(Locale.getDefault())) {
            "MEDICA" -> "100" to "Emergencia médica"
            "PANICO" -> "110" to "Pánico"
            else -> "120" to "Incendio"
        }
        return alert(
            id = System.currentTimeMillis(),
            minutesAgo = 0,
            code = code,
            description = description,
            imei = imei,
            actor = actor,
            latitude = -34.6037,
            longitude = -58.3816
        )
    }

    private fun alert(
        id: Long,
        minutesAgo: Int,
        code: String,
        description: String,
        imei: String,
        actor: String? = null,
        subject: String? = null,
        subjectNumber: Int? = null,
        zoneName: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ) = ServerAlert(
        id = id,
        receivedAt = isoTimestamp(System.currentTimeMillis() - minutesAgo * 60_000L),
        eventCode = code,
        eventDescription = description,
        partition = "01",
        subject = subject,
        subjectNumber = subjectNumber,
        subjectKind = subject?.let { "ZONA" },
        zoneName = zoneName,
        abonado = "2010",
        imei = imei,
        actionSource = actor?.let { "APP" },
        actorName = actor,
        latitude = latitude,
        longitude = longitude,
        locationAccuracyMeters = latitude?.let { 12.0 },
        locationCapturedAt = latitude?.let { isoTimestamp(System.currentTimeMillis()) }
    )

    private fun isoTimestamp(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(time))
}
