package com.nanocomm.nanosmart.eventos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PanelsActivity : SecureActivity() {

    private lateinit var panelsList: RecyclerView
    private lateinit var txtPanelCount: TextView
    private lateinit var txtEmptyPanels: TextView
    private lateinit var adapter: PanelListAdapter
    private var initialSetupLaunched = false
    private var pushReceiverRegistered = false
    private val refreshedStatusImeis = ConcurrentHashMap.newKeySet<String>()
    private val statusExecutor: ExecutorService = Executors.newFixedThreadPool(STATUS_WORKERS)
    private val pushReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::adapter.isInitialized) adapter.notifyVisibleStatusesChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panels)

        panelsList = findViewById(R.id.panelsList)
        txtPanelCount = findViewById(R.id.txtPanelCount)
        txtEmptyPanels = findViewById(R.id.txtEmptyPanels)
        adapter = PanelListAdapter(
            statusProvider = { imei -> Prefs.statusForImei(this, imei) },
            onOpen = { panel -> openPanel(panel.imei) },
            onEdit = { panel -> editPanel(panel.imei) },
            onVisible = ::refreshVisiblePanelStatus
        )
        panelsList.layoutManager = LinearLayoutManager(this)
        panelsList.adapter = adapter

        val btnAddPanel = findViewById<Button>(R.id.btnAddPanel)
        InteractionFeedback.install(btnAddPanel, FeedbackKind.NAVIGATION)
        btnAddPanel.setOnClickListener {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_ADD_PANEL, true)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isSecurityUnlocked) return
        resumeSecuredContent()
        registerPushReceiver()
    }

    override fun onSecurityUnlocked() {
        resumeSecuredContent()
        registerPushReceiver()
    }

    private fun resumeSecuredContent() {
        val panels = Prefs.panels(this)
        refreshedStatusImeis.clear()
        renderPanels(panels)
        adapter.notifyVisibleStatusesChanged()
        if (panels.isEmpty() && !initialSetupLaunched) {
            initialSetupLaunched = true
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_ADD_PANEL, true)
            )
        }
    }

    private fun renderPanels(panels: List<PanelConfig>) {
        txtPanelCount.text = when (panels.size) {
            0 -> "Todavía no cargaste ningún panel"
            1 -> "1 panel vinculado"
            else -> "${panels.size} paneles vinculados"
        }
        txtEmptyPanels.visibility = if (panels.isEmpty()) View.VISIBLE else View.GONE
        panelsList.visibility = if (panels.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitPanels(panels)
    }

    private fun editPanel(imei: String) {
        if (!Prefs.selectPanel(this, imei)) return
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun openPanel(imei: String) {
        if (!Prefs.selectPanel(this, imei)) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PANEL_IMEI, imei)
        )
    }

    private fun refreshVisiblePanelStatus(panel: PanelConfig) {
        if (DemoMode.enabled || panel.accessToken.isBlank()) return
        if (!refreshedStatusImeis.add(panel.imei)) return

        statusExecutor.execute {
            val response = runCatching {
                AlertApiClient.fetchDeviceStatus(panel.accessToken)
            }.getOrNull() ?: return@execute
            val panelState = response.panelState
            val latest = response.latestCommand?.takeIf { it.status == "CONFIRMED" }
            val status = panelState?.panelStatus ?: latest?.panelStatus ?: return@execute
            Prefs.setStatusForImei(this, panel.imei, status)
            Prefs.setLastActionActorForImei(
                this,
                panel.imei,
                panelState?.actorName ?: latest?.actorName,
                panelState?.actionSource ?: latest?.actionSource
            )
            runOnUiThread {
                if (!isFinishing && !isDestroyed) adapter.notifyStatusChanged(panel.imei)
            }
        }
    }

    override fun onDestroy() {
        statusExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        if (pushReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(pushReceiver)
            pushReceiverRegistered = false
        }
        super.onPause()
    }

    private fun registerPushReceiver() {
        if (pushReceiverRegistered) return
        LocalBroadcastManager.getInstance(this).registerReceiver(
            pushReceiver,
            IntentFilter(NanoSmartMessagingService.ACTION_PUSH_ALERT)
        )
        pushReceiverRegistered = true
    }

    private companion object {
        const val STATUS_WORKERS = 3
    }
}
