package com.nanocomm.nanosmart.eventos

/**
 * Configuración interna para las pruebas mediante la IP pública de AWS.
 *
 * Los valores no se muestran ni se pueden cambiar desde la interfaz. La
 * ofuscación evita que la dirección quede como texto plano en el código, pero
 * no reemplaza HTTPS ni constituye protección criptográfica de producción.
 */
object ServerConfig {
    private const val XOR_KEY = 0x5A

    private val encodedHost = intArrayOf(
        111, 110, 116, 104, 105, 104, 116, 107, 107, 111, 116, 107, 106, 108
    )
    private val encodedPort = intArrayOf(107, 98, 106, 98, 104)

    private fun reveal(values: IntArray): String =
        values.joinToString(separator = "") { value -> (value xor XOR_KEY).toChar().toString() }

    val baseUrl: String
        get() = "http://${reveal(encodedHost)}:${reveal(encodedPort)}"

    fun alertsUrl(afterId: Long?): String = buildString {
        append(baseUrl)
        append("/api/app/alerts?take=${AlertDisplayPolicy.HISTORY_LIMIT}")
        if (afterId != null && afterId > 0L) {
            append("&afterId=")
            append(afterId)
        }
    }

    val pushTokenUrl: String
        get() = "$baseUrl/api/app/push-token"

    val registrationUrl: String
        get() = "$baseUrl/api/app/register"

    val deviceStatusUrl: String
        get() = "$baseUrl/api/app/device/status"

    val zoneNamesUrl: String
        get() = "$baseUrl/api/app/device/zones"

    val deviceCommandUrl: String
        get() = "$baseUrl/api/app/device/command"

    val emergencyUrl: String
        get() = "$baseUrl/api/app/emergency"

    val installationNameUrl: String
        get() = "$baseUrl/api/app/me/name"

    fun deviceCommandStatusUrl(commandId: String): String =
        "$baseUrl/api/app/device/commands/$commandId"

    const val POLL_INTERVAL_MS = 30_000L
}
