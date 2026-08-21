package com.nanocomm.nanosmart.eventos

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : SecureActivity() {

    private lateinit var txtPanel: TextView
    private lateinit var txtStatus: TextView
    private lateinit var btnRefresh: Button
    private lateinit var container: LinearLayout
    private var requestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        txtPanel = findViewById(R.id.txtHistoryPanel)
        txtStatus = findViewById(R.id.txtHistoryStatus)
        btnRefresh = findViewById(R.id.btnRefreshHistory)
        container = findViewById(R.id.historyContainer)

        txtPanel.text = Prefs.panelName(this).ifBlank { "Panel ${Prefs.imei(this).takeLast(4)}" }
        val btnBack = findViewById<Button>(R.id.btnHistoryBack)
        InteractionFeedback.install(btnBack, FeedbackKind.NAVIGATION)
        InteractionFeedback.install(btnRefresh, FeedbackKind.NAVIGATION)
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { loadHistory() }
    }

    override fun onResume() {
        super.onResume()
        if (!isSecurityUnlocked) return
        loadHistory()
    }

    override fun onSecurityUnlocked() {
        loadHistory()
    }

    private fun loadHistory() {
        if (requestInFlight || !::container.isInitialized) return
        if (DemoMode.enabled) {
            renderHistory(DemoMode.alerts(Prefs.imei(this)))
            return
        }
        val token = Prefs.token(this).trim()
        if (token.isEmpty()) {
            txtStatus.text = "El panel no tiene un token configurado"
            showEmpty("No se pudo consultar el histórico")
            return
        }

        requestInFlight = true
        btnRefresh.isEnabled = false
        txtStatus.text = "Consultando las últimas ${AlertDisplayPolicy.HISTORY_LIMIT} alertas…"
        Thread {
            val result = runCatching {
                AlertApiClient.fetchAlerts(token, Prefs.imei(this).trim(), null)
                    .alerts
                    .sortedByDescending { it.id }
                    .let { AlertDisplayPolicy.history(it) }
            }
            runOnUiThread {
                requestInFlight = false
                btnRefresh.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(::renderHistory).onFailure { error ->
                    txtStatus.text = error.message ?: "No se pudo conectar con NanoSmart Server"
                    showEmpty("Histórico no disponible")
                }
            }
        }.start()
    }

    private fun renderHistory(alerts: List<ServerAlert>) {
        val updateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        txtStatus.text = if (alerts.isEmpty()) {
            "Sin alertas guardadas · $updateTime"
        } else {
            "${alerts.size} alerta(s), máximo ${AlertDisplayPolicy.HISTORY_LIMIT} · $updateTime"
        }
        container.removeAllViews()
        if (alerts.isEmpty()) {
            showEmpty("Todavía no hay alertas para este panel")
            return
        }
        alerts.forEach { alert -> container.addView(createAlertCard(alert)) }
    }

    private fun createAlertCard(alert: ServerAlert): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_alert_card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        card.addView(TextView(this).apply {
            text = alert.eventDescription
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.m41_red))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })

        val subjectKind = alert.subjectKind?.uppercase(Locale.getDefault())
        val subjectLabel = if (subjectKind == "USUARIO") "Usuario" else "Zona"
        val details = buildList {
            alert.actorName?.let { add("Ejecutado por $it") }
            alert.partition?.let { add("Partición $it") }
            alert.subject?.let { subject ->
                val zoneNumber = alert.subjectNumber ?: subject.toIntOrNull()
                if (subjectKind == "ZONA" && !alert.zoneName.isNullOrBlank() && zoneNumber != null) {
                    add("${alert.zoneName} (Zona $zoneNumber)")
                } else {
                    add("$subjectLabel $subject")
                }
            }
            alert.abonado?.let { add("Abonado $it") }
            if (alert.latitude != null && alert.longitude != null) {
                val accuracy = alert.locationAccuracyMeters?.toInt()?.takeIf { it >= 0 }
                add(accuracy?.let { "Ubicación disponible (±${it} m)" } ?: "Ubicación disponible")
            }
        }
        card.addView(TextView(this).apply {
            text = details.joinToString(" · ").ifEmpty { "Sin detalle adicional" }
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.m41_text_secondary))
            textSize = 13f
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "Evento ${alert.eventCode} · ${formatAlertDate(alert.receivedAt)}"
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.m41_text_primary))
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
        })

        val latitude = alert.latitude
        val longitude = alert.longitude
        if (latitude != null && longitude != null &&
            EmergencyLocationPolicy.validCoordinates(latitude, longitude)
        ) {
            val mapButton = Button(this).apply {
                text = "Ver ubicación en Google Maps"
                isAllCaps = false
                setBackgroundResource(R.drawable.bg_button_secondary)
                backgroundTintList = null
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.m41_button_text))
                setOnClickListener {
                    runCatching { startActivity(EmergencyMapLink.intent(latitude, longitude)) }
                        .onFailure {
                            Toast.makeText(
                                this@HistoryActivity,
                                "No se encontró una aplicación para abrir el mapa",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            InteractionFeedback.install(mapButton, FeedbackKind.NAVIGATION)
            card.addView(mapButton)
        }

        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        return card
    }

    private fun showEmpty(message: String) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.m41_text_secondary))
            textSize = 14f
            setPadding(dp(4), dp(18), dp(4), dp(18))
        })
    }

    private fun formatAlertDate(raw: String): String {
        if (raw.isBlank()) return "Fecha no disponible"
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).parse(raw)
        }.getOrNull() ?: return raw
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(parsed)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
