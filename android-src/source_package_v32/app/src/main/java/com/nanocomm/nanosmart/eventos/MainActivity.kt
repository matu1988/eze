package com.nanocomm.nanosmart.eventos

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : SecureActivity() {

    private lateinit var txtNumber: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLastAction: TextView
    private lateinit var txtToolbarStatus: TextView
    private lateinit var txtCircle: TextView
    private lateinit var btnArm: Button
    private lateinit var btnDisarm: Button
    private lateinit var btnPanico: Button
    private lateinit var btnMedica: Button
    private lateinit var btnIncendio: Button
    private lateinit var btnHistory: Button
    private lateinit var btnPanels: Button
    private lateinit var txtAlertsStatus: TextView
    private lateinit var alertsContainer: LinearLayout
    private lateinit var btnRefreshAlerts: Button
    private lateinit var panelStateCard: View

    private var statusDialog: AlertDialog? = null
    private var alertRequestInFlight = false
    private var alertRefreshPending = false
    private var commandRequestInFlight = false
    private var emergencyRequestInFlight = false
    private var statusReceiverRegistered = false
    private var activeAlertToken: String? = null
    private var lastFetchedAlertId = 0L
    private val displayedAlerts = mutableListOf<ServerAlert>()
    private var renderedStatus: String? = null
    private val alertHandler = Handler(Looper.getMainLooper())
    private val alertPoller = object : Runnable {
        override fun run() {
            loadLatestDeviceState()
            loadAlerts(showLoading = false)
            alertHandler.postDelayed(this, ServerConfig.POLL_INTERVAL_MS)
        }
    }

    private val statusReceiver: BroadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                NanoSmartMessagingService.ACTION_PUSH_ALERT -> {
                    applyStatus(
                        Prefs.status(this@MainActivity),
                        Prefs.lastActionActor(this@MainActivity),
                        Prefs.lastActionSource(this@MainActivity)
                    )
                    loadAlerts(showLoading = false)
                }
            }
        }
    }

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            EmergencyLocationProvider.refresh(this)
        }
    }


    private fun showStatusDialog(message: String) {
        if (statusDialog?.isShowing == true) {
            statusDialog?.dismiss()
        }
        statusDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        statusDialog?.show()
    }

    private fun dismissStatusDialog() {
        statusDialog?.dismiss()
        statusDialog = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.getStringExtra(EXTRA_PANEL_IMEI)?.let { Prefs.selectPanel(this, it) }

        if (!Prefs.isConfigured(this)) {
            startActivity(Intent(this, PanelsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        txtNumber = findViewById(R.id.txtNumber)
        txtStatus = findViewById(R.id.txtStatus)
        txtLastAction = findViewById(R.id.txtLastAction)
        txtToolbarStatus = findViewById(R.id.txtToolbarStatus)
        txtCircle = findViewById(R.id.txtCircle)
        btnArm = findViewById(R.id.btnArm)
        btnDisarm = findViewById(R.id.btnDisarm)
        btnPanico = findViewById(R.id.btnPanico)
        btnMedica = findViewById(R.id.btnMedica)
        btnIncendio = findViewById(R.id.btnIncendio)
        btnHistory = findViewById(R.id.btnHistory)
        btnPanels = findViewById(R.id.btnPanels)
        txtAlertsStatus = findViewById(R.id.txtAlertsStatus)
        alertsContainer = findViewById(R.id.alertsContainer)
        btnRefreshAlerts = findViewById(R.id.btnRefreshAlerts)
        panelStateCard = findViewById(R.id.panelStateCard)

        InteractionFeedback.install(btnArm, FeedbackKind.CONTROL)
        InteractionFeedback.install(btnDisarm, FeedbackKind.CONTROL)
        InteractionFeedback.install(btnMedica, FeedbackKind.EMERGENCY)
        InteractionFeedback.install(btnPanico, FeedbackKind.EMERGENCY)
        InteractionFeedback.install(btnIncendio, FeedbackKind.EMERGENCY)
        InteractionFeedback.install(btnHistory, FeedbackKind.NAVIGATION)
        InteractionFeedback.install(btnPanels, FeedbackKind.NAVIGATION)
        InteractionFeedback.install(btnRefreshAlerts, FeedbackKind.NAVIGATION)

        refreshPanelHeader()
        applyStatus(Prefs.status(this), Prefs.lastActionActor(this), Prefs.lastActionSource(this))

        btnArm.setOnClickListener {
            showStatusDialog("Realizando proceso de armado")
            sendGprsCommand("ARMAR")
        }
        btnDisarm.setOnClickListener {
            authenticateSensitiveAction(
                title = "Confirmar desarmado",
                subtitle = "Verificá tu identidad para desarmar el panel"
            ) {
                showStatusDialog("Realizando proceso de desarmado")
                sendGprsCommand("DESARMAR")
            }
        }

        btnMedica.setOnClickListener {
            sendEmergencyEvent(EmergencyType.MEDICA)
        }
        btnPanico.setOnClickListener {
            sendEmergencyEvent(EmergencyType.PANICO)
        }
        btnIncendio.setOnClickListener {
            sendEmergencyEvent(EmergencyType.INCENDIO)
        }


        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        btnPanels.setOnClickListener {
            startActivity(
                Intent(this, PanelsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            finish()
        }

        btnRefreshAlerts.setOnClickListener {
            loadAlerts(showLoading = true)
        }

    }

    override fun onResume() {
        super.onResume()
        if (!isSecurityUnlocked) return
        resumeSecuredContent()
    }

    override fun onSecurityUnlocked() {
        resumeSecuredContent()
    }

    private fun resumeSecuredContent() {
        if (!::txtNumber.isInitialized) return
        refreshPanelHeader()
        applyStatus(Prefs.status(this), Prefs.lastActionActor(this), Prefs.lastActionSource(this))
        if (DemoMode.enabled) {
            displayedAlerts.clear()
            displayedAlerts.addAll(DemoMode.alerts(Prefs.imei(this)))
            renderAlerts(displayedAlerts)
            return
        }
        if (!statusReceiverRegistered) {
            LocalBroadcastManager.getInstance(this)
                .registerReceiver(statusReceiver, IntentFilter().apply {
                    addAction(NanoSmartMessagingService.ACTION_PUSH_ALERT)
                })
            statusReceiverRegistered = true
        }
        startAlertPolling()
        ensurePermissions()
        EmergencyLocationProvider.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedImei = intent.getStringExtra(EXTRA_PANEL_IMEI) ?: return
        if (!Prefs.selectPanel(this, requestedImei) || !::txtNumber.isInitialized) return
        stopAlertPolling()
        alertRefreshPending = false
        activeAlertToken = null
        lastFetchedAlertId = 0L
        displayedAlerts.clear()
        refreshPanelHeader()
        applyStatus(Prefs.status(this), Prefs.lastActionActor(this), Prefs.lastActionSource(this))
        startAlertPolling()
    }

    private fun refreshPanelHeader() {
        val panelName = Prefs.panelName(this)
        val abonado = Prefs.abonado(this).trim()
        txtNumber.text = when {
            panelName.isNotBlank() && abonado.isNotBlank() -> "$panelName · Abonado $abonado"
            panelName.isNotBlank() -> panelName
            abonado.isNotBlank() -> "Abonado $abonado"
            else -> "Panel ${Prefs.imei(this).takeLast(4)}"
        }
    }

    override fun onPause() {
        if (!::txtNumber.isInitialized) {
            super.onPause()
            return
        }
        stopAlertPolling()
        alertRefreshPending = false
        if (statusReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        super.onPause()
    }

    private fun startAlertPolling() {
        alertHandler.removeCallbacks(alertPoller)
        if (DemoMode.enabled) {
            displayedAlerts.clear()
            displayedAlerts.addAll(DemoMode.alerts(Prefs.imei(this)))
            renderAlerts(displayedAlerts)
            return
        }
        loadLatestDeviceState()
        loadAlerts(showLoading = true)
        alertHandler.postDelayed(alertPoller, ServerConfig.POLL_INTERVAL_MS)
    }

    private fun stopAlertPolling() {
        alertHandler.removeCallbacks(alertPoller)
    }

    private fun loadAlerts(showLoading: Boolean) {
        if (alertRequestInFlight) {
            alertRefreshPending = true
            return
        }
        if (DemoMode.enabled) {
            displayedAlerts.clear()
            displayedAlerts.addAll(DemoMode.alerts(Prefs.imei(this)))
            renderAlerts(displayedAlerts)
            return
        }

        val token = Prefs.token(this).trim()
        if (token.isEmpty()) {
            txtAlertsStatus.text = "Ingresá el token desde Configuración para recibir alertas."
            showEmptyAlerts("Token no configurado")
            return
        }

        if (activeAlertToken != token) {
            activeAlertToken = token
            lastFetchedAlertId = 0L
            displayedAlerts.clear()
        }

        alertRequestInFlight = true
        btnRefreshAlerts.isEnabled = false
        if (showLoading) {
            txtAlertsStatus.text = "Consultando alertas…"
        }

        val expectedImei = Prefs.imei(this).trim()
        val afterId = lastFetchedAlertId.takeIf { it > 0L }
        Thread {
            val result = runCatching {
                AlertApiClient.fetchAlerts(token, expectedImei, afterId)
            }

            runOnUiThread {
                alertRequestInFlight = false
                val refreshAgain = alertRefreshPending
                alertRefreshPending = false
                if (::btnRefreshAlerts.isInitialized) {
                    btnRefreshAlerts.isEnabled = true
                }
                if (isFinishing || isDestroyed) return@runOnUiThread

                result.onSuccess { response ->
                    mergeAndRenderAlerts(response.alerts)
                }.onFailure { error ->
                    val detail = error.message?.takeIf { it.isNotBlank() }
                        ?: "No se pudo conectar con NanoSmart Server"
                    txtAlertsStatus.text = detail
                    if (alertsContainer.childCount == 0) {
                        showEmptyAlerts("Sin conexión con el servidor")
                    }
                }
                if (refreshAgain && !isFinishing && !isDestroyed) {
                    loadAlerts(showLoading = false)
                }
            }
        }.start()
    }

    private fun mergeAndRenderAlerts(incoming: List<ServerAlert>) {
        if (incoming.isNotEmpty()) {
            val merged = (displayedAlerts + incoming)
                .associateBy { it.id }
                .values
                .sortedByDescending { it.id }
                .take(AlertDisplayPolicy.HISTORY_LIMIT)
            displayedAlerts.clear()
            displayedAlerts.addAll(merged)

            val newestId = incoming.maxOf { it.id }
            if (newestId > lastFetchedAlertId) {
                lastFetchedAlertId = newestId
            }
        }

        renderAlerts(displayedAlerts)
    }

    private fun renderAlerts(alerts: List<ServerAlert>) {
        val updateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        txtAlertsStatus.text = if (alerts.isEmpty()) {
            "Conectado · Sin alertas · $updateTime"
        } else {
            "Conectado · ${alerts.size} alerta(s) guardadas · $updateTime"
        }

        alertsContainer.removeAllViews()
        if (alerts.isEmpty()) {
            showEmptyAlerts("Todavía no hay alertas para este equipo")
            return
        }

        val visibleAlerts = AlertDisplayPolicy.main(alerts)
        for (alert in visibleAlerts) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_alert_card)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }

            val title = TextView(this).apply {
                text = alert.eventDescription
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m41_red))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(title)

            val subjectKind = alert.subjectKind?.uppercase(Locale.getDefault())
            val subjectLabel = when (subjectKind) {
                "USUARIO" -> "Usuario"
                else -> "Zona"
            }
            val detailParts = buildList {
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
                    add(
                        accuracy?.let { "Ubicación disponible (±${it} m)" }
                            ?: "Ubicación disponible"
                    )
                }
            }
            val details = TextView(this).apply {
                text = detailParts.joinToString(" · ").ifEmpty { "Sin detalle adicional" }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m41_text_secondary))
                textSize = 13f
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(details)

            val footer = TextView(this).apply {
                text = "Evento ${alert.eventCode} · ${formatAlertDate(alert.receivedAt)}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m41_text_primary))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(footer)

            val latitude = alert.latitude
            val longitude = alert.longitude
            if (latitude != null && longitude != null &&
                EmergencyLocationPolicy.validCoordinates(latitude, longitude)
            ) {
                card.addView(Button(this).apply {
                    text = "Ver ubicación en Google Maps"
                    isAllCaps = false
                    setOnClickListener {
                        runCatching {
                            startActivity(EmergencyMapLink.intent(latitude, longitude))
                        }.onFailure {
                            Toast.makeText(
                                this@MainActivity,
                                "No se encontró una aplicación para abrir el mapa",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                })
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            alertsContainer.addView(card, params)
        }

        if (alerts.size > visibleAlerts.size) {
            alertsContainer.addView(TextView(this).apply {
                text = "${alerts.size - visibleAlerts.size} alerta(s) más disponibles en Histórico"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m41_text_secondary))
                textSize = 13f
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
        }

        notifyIfNewAlert(alerts)
    }

    private fun notifyIfNewAlert(alerts: List<ServerAlert>) {
        val newest = alerts.maxByOrNull { it.id } ?: return
        if (newest.id <= 0L) return

        val previousId = Prefs.lastAlertId(this)
        if (previousId > 0L && newest.id > previousId) {
            InteractionFeedback.alert(this)
            Toast.makeText(
                this,
                "Nueva alerta: ${newest.eventDescription}",
                Toast.LENGTH_LONG
            ).show()
        }
        if (newest.id > previousId) {
            Prefs.setLastAlertId(this, newest.id)
        }
    }

    private fun showEmptyAlerts(message: String) {
        alertsContainer.removeAllViews()
        alertsContainer.addView(TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.m41_text_secondary))
            textSize = 13f
        })
    }

    private fun formatAlertDate(raw: String): String {
        if (raw.isBlank()) return "Fecha no disponible"
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).parse(raw)
        }.getOrNull() ?: return raw
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(parsed)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun ensurePermissions() {
        if (DemoMode.enabled) return
        val requested = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requested.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requested.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        requested.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val need = requested.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            permsLauncher.launch(need.toTypedArray())
        }
    }

    private fun applyStatus(status: String, actorName: String? = null, actionSource: String? = null) {
        Prefs.setStatus(this, status)
        if (!actorName.isNullOrBlank()) {
            Prefs.setLastActionActor(this, actorName, actionSource)
        }
        val upper = status.uppercase(Locale.getDefault())
        when (upper) {
            "ARMADO" -> {
                txtStatus.text = "Alarma armada"
                txtStatus.setBackgroundResource(R.drawable.bg_status_armed_chip)
                txtCircle.text = "A"
                txtCircle.setBackgroundResource(R.drawable.bg_circle_red)
                txtToolbarStatus.setBackgroundResource(R.drawable.bg_status_armed_chip)
            }
            "DESARMADO" -> {
                txtStatus.text = "Alarma desarmada"
                txtStatus.setBackgroundResource(R.drawable.bg_status_disarmed_chip)
                txtCircle.text = "D"
                txtCircle.setBackgroundResource(R.drawable.bg_circle_green)
                txtToolbarStatus.setBackgroundResource(R.drawable.bg_status_disarmed_chip)
            }
            else -> {
                txtStatus.text = status
                txtStatus.setBackgroundResource(R.drawable.bg_status_unknown_chip)
                txtCircle.text = "-"
                txtCircle.setBackgroundResource(R.drawable.bg_circle_grey)
                txtToolbarStatus.setBackgroundResource(R.drawable.bg_status_unknown_chip)
            }
        }
        txtToolbarStatus.text = upper
        txtStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        txtToolbarStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        val actor = actorName?.takeIf { it.isNotBlank() }
            ?: Prefs.lastActionActor(this).takeIf { it.isNotBlank() }
        txtLastAction.text = actor?.let { "Última acción: $it" }
            ?: "Última acción: sin identificar"

        if (renderedStatus != upper) {
            renderedStatus = upper
            txtCircle.animate().cancel()
            txtCircle.scaleX = 0.82f
            txtCircle.scaleY = 0.82f
            txtCircle.alpha = 0.45f
            txtCircle.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(280L)
                .start()
            panelStateCard.animate().cancel()
            panelStateCard.animate()
                .translationY(-dp(3).toFloat())
                .setDuration(120L)
                .withEndAction {
                    panelStateCard.animate().translationY(0f).setDuration(180L).start()
                }
                .start()
        }
    }

    private fun sendGprsCommand(action: String) {
        if (commandRequestInFlight) return
        if (DemoMode.enabled) {
            dismissStatusDialog()
            val panelStatus = action.toPanelStatus()
            applyStatus(panelStatus, "Usuario Demo", "APP")
            Toast.makeText(
                this,
                "Simulación: panel ${panelStatus.lowercase(Locale.getDefault())}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val token = Prefs.token(this).trim()
        if (token.isEmpty()) {
            dismissStatusDialog()
            Toast.makeText(this, "Ingresá el token desde Configuración", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }

        commandRequestInFlight = true
        btnArm.isEnabled = false
        btnDisarm.isEnabled = false
        Thread {
            val result = runCatching {
                val created = AlertApiClient.sendDeviceCommand(token, action, Prefs.name(this))
                waitForCommandDelivery(token, created)
            }
            runOnUiThread {
                commandRequestInFlight = false
                if (::btnArm.isInitialized) {
                    btnArm.isEnabled = true
                    btnDisarm.isEnabled = true
                }
                if (isFinishing || isDestroyed) return@runOnUiThread
                dismissStatusDialog()
                result.onSuccess { command ->
                    when (command.status) {
                        "CONFIRMED" -> {
                            applyStatus(
                                command.panelStatus ?: command.action.toPanelStatus(),
                                command.actorName,
                                command.actionSource
                            )
                            Toast.makeText(
                                this,
                                (command.resultDescription ?: if (command.alreadyInState) {
                                    "El panel ya estaba ${command.panelStatus?.lowercase() ?: "en ese estado"}"
                                } else {
                                    "Estado del panel confirmado"
                                }) + (command.actorName?.let { " · $it" } ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        "EXPIRED" -> Toast.makeText(
                            this,
                            command.error ?: "El equipo no respondió",
                            Toast.LENGTH_LONG
                        ).show()
                        else -> Toast.makeText(
                            this,
                            if (command.status == "AWAITING_RESULT") {
                                "La orden fue enviada; falta la confirmación del panel"
                            } else {
                                "La orden quedó pendiente de respuesta del equipo"
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "No se pudo enviar la orden",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun waitForCommandDelivery(
        token: String,
        initial: DeviceCommand
    ): DeviceCommand {
        var command = initial
        val limit = System.currentTimeMillis() + 90_000L
        while (command.status in setOf("PENDING", "DELIVERING", "AWAITING_RESULT") &&
            System.currentTimeMillis() < limit) {
            Thread.sleep(1_000L)
            command = AlertApiClient.fetchDeviceCommand(token, command.id)
        }
        return command
    }

    private fun loadLatestDeviceState() {
        if (DemoMode.enabled) return
        val token = Prefs.token(this).trim()
        if (token.isEmpty()) return
        Thread {
            val deviceStatus = runCatching { AlertApiClient.fetchDeviceStatus(token) }.getOrNull()
                ?: return@Thread
            val panelState = deviceStatus.panelState
            val latestCommand = deviceStatus.latestCommand?.takeIf { it.status == "CONFIRMED" }
            val confirmedStatus = panelState?.panelStatus ?: latestCommand?.panelStatus ?: return@Thread
            val actorName = panelState?.actorName ?: latestCommand?.actorName
            val actionSource = panelState?.actionSource ?: latestCommand?.actionSource
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    applyStatus(confirmedStatus, actorName, actionSource)
                }
            }
        }.start()
    }

    private fun String.toPanelStatus(): String =
        if (uppercase(Locale.getDefault()) == "ARMAR") "ARMADO" else "DESARMADO"

    private fun sendEmergencyEvent(type: EmergencyType) {
        if (emergencyRequestInFlight) return
        if (DemoMode.enabled) {
            val simulated = DemoMode.emergencyAlert(
                type = type.name,
                imei = Prefs.imei(this),
                actor = "Usuario Demo"
            )
            mergeAndRenderAlerts(listOf(simulated))
            Toast.makeText(
                this,
                "Simulación: aviso de ${simulated.eventDescription.lowercase(Locale.getDefault())}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val token = Prefs.token(this).trim()
        val serviceMode = Prefs.serviceMode(this)
        val monitoringIp = Prefs.ip(this).trim()
        val monitoringPort = Prefs.port(this)
        if (token.isEmpty()) {
            Toast.makeText(
                this,
                "Verificá el token de NanoSmart Server",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (serviceMode == ServiceMode.MONITORING &&
            (monitoringIp.isEmpty() || monitoringPort == 0)
        ) {
            Toast.makeText(
                this,
                "Verificá el token, la IP y el puerto de monitoreo",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        emergencyRequestInFlight = true
        setEmergencyButtonsEnabled(false)
        val emergencyLocation = EmergencyLocationProvider.bestLastKnown(this)
        Thread {
            val monitoringResult = if (serviceMode == ServiceMode.MONITORING) {
                runCatching { sendMonitoringUdp(type) }
            } else {
                null
            }
            val identityResult = runCatching {
                AlertApiClient.sendEmergency(
                    token,
                    type.name,
                    Prefs.name(this),
                    Prefs.abonado(this),
                    emergencyLocation
                )
            }
            runOnUiThread {
                emergencyRequestInFlight = false
                setEmergencyButtonsEnabled(true)
                if (isFinishing || isDestroyed) return@runOnUiThread
                val alert = identityResult.getOrNull()
                if (alert != null) {
                    mergeAndRenderAlerts(listOf(alert))
                }
                val locationStatus = if (alert?.latitude != null && alert.longitude != null) {
                    " · ubicación incluida"
                } else {
                    " · ubicación no disponible"
                }
                when {
                    serviceMode == ServiceMode.SELF_MONITORING && alert != null -> Toast.makeText(
                        this,
                        "${alert.eventDescription} enviado al receptor y a tus celulares por ${alert.actorName ?: Prefs.name(this)}$locationStatus",
                        Toast.LENGTH_LONG
                    ).show()
                    serviceMode == ServiceMode.SELF_MONITORING -> Toast.makeText(
                        this,
                        "No se pudo enviar al receptor: ${identityResult.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    monitoringResult?.isSuccess == true && alert != null -> Toast.makeText(
                        this,
                        "${alert.eventDescription} enviado al monitoreo por ${alert.actorName ?: Prefs.name(this)}$locationStatus",
                        Toast.LENGTH_LONG
                    ).show()
                    monitoringResult?.isSuccess == true -> Toast.makeText(
                        this,
                        "Enviado al monitoreo, pero no se pudo registrar quién lo ejecutó",
                        Toast.LENGTH_LONG
                    ).show()
                    alert != null -> Toast.makeText(
                        this,
                        "Se registró ${alert.actorName ?: Prefs.name(this)}$locationStatus, pero falló el envío al monitoreo: ${monitoringResult?.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    else -> Toast.makeText(
                        this,
                        "No se pudo enviar al monitoreo: ${monitoringResult?.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun sendMonitoringUdp(type: EmergencyType) {
        val ip = Prefs.ip(this)
        val port = Prefs.port(this)
        val id = Prefs.id(this)
        val imei = Prefs.imei(this)
        val abonadoBase = Prefs.abonado(this)
        val clave = Prefs.clave(this)
        if (ip.isEmpty() || port == 0 || id.isEmpty() || imei.isEmpty() ||
            abonadoBase.isEmpty() || clave.isEmpty()
        ) {
            throw IllegalStateException("faltan datos de configuración UDP")
        }

        val abonadoField = abonadoBase + when (type) {
            EmergencyType.MEDICA -> "181100010000"
            EmergencyType.PANICO -> "181110010000"
            EmergencyType.INCENDIO -> "181120010000"
        }
        val dateStr = SimpleDateFormat("dd/MM/yyyy-HH:mm", Locale.getDefault()).format(Date())
        val counterStr = String.format(Locale.US, "%02d", Prefs.nextCounterForSend(this))
        val payload = buildString {
            append("$")
            append("B,")
            append(id)
            append(",")
            append(counterStr)
            append(",")
            append(dateStr)
            append(",01,")
            append(abonadoField)
            append(",18,0,0,")
            append(clave)
            append(",15,MA_1.90GE-AR,0,0,0,0,0,0,0,")
            append(imei)
            append(",0,0,")
            append(ip)
            append(",")
            append(port)
            append(",00,10,4G,")
            append("$")
            append("E")
        }
        val data = payload.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
        }
    }

    private fun setEmergencyButtonsEnabled(enabled: Boolean) {
        btnMedica.isEnabled = enabled
        btnPanico.isEnabled = enabled
        btnIncendio.isEnabled = enabled
    }

    private enum class EmergencyType {
        PANICO, MEDICA, INCENDIO
    }

    companion object {
        const val EXTRA_PANEL_IMEI = "panel_imei"
    }
}
