package com.nanocomm.nanosmart.eventos

data class QrPairing(
    val imei: String,
    val accessKey: String
)

object QrPairingParser {
    private const val VERSION = "NS1"
    private val imeiPattern = Regex("\\d{15}")
    private val accessKeyPattern = Regex("NS-[A-Z0-9]{4}(-[A-Z0-9]{4}){3}")

    fun parse(rawValue: String): QrPairing? {
        val parts = rawValue.trim().split('|', limit = 3)
        val imei = parts.getOrNull(1).orEmpty().trim()
        val accessKey = parts.getOrNull(2).orEmpty().trim().uppercase()
        if (parts.firstOrNull() != VERSION ||
            !imei.matches(imeiPattern) ||
            !accessKey.matches(accessKeyPattern)
        ) {
            return null
        }
        return QrPairing(imei, accessKey)
    }
}
