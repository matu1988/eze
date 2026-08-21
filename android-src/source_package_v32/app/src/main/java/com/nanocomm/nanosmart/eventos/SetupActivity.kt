package com.nanocomm.nanosmart.eventos

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class SetupActivity : SecureActivity() {

    private lateinit var edtPanelName: EditText
    private lateinit var edtClave: EditText
    private lateinit var edtId: EditText
    private lateinit var edtImei: EditText
    private lateinit var edtAbonado: EditText
    private lateinit var edtIp: EditText
    private lateinit var edtPort: EditText
    private lateinit var edtToken: EditText
    private lateinit var edtNombre: EditText
    private lateinit var spnServiceMode: Spinner
    private lateinit var txtModeDescription: TextView
    private lateinit var monitoringFieldsContainer: View
    private lateinit var abonadoContainer: View
    private lateinit var panelConfigContainer: View
    private lateinit var zoneCustomizationContainer: View
    private lateinit var zonesEditorContainer: LinearLayout
    private lateinit var txtZoneStatus: TextView
    private lateinit var btnPanelConfigTab: Button
    private lateinit var btnZoneCustomizationTab: Button
    private lateinit var btnSaveZones: Button
    private lateinit var btnScanQr: Button
    private lateinit var txtQrStatus: TextView
    private val zoneInputs = linkedMapOf<Int, EditText>()
    private var originalImei: String? = null
    private var addMode = false
    private var zonesLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        addMode = intent.getBooleanExtra(EXTRA_ADD_PANEL, false) || Prefs.panels(this).isEmpty()
        val title: TextView = findViewById(R.id.txtSetupTitle)
        edtPanelName = findViewById(R.id.edtPanelName)
        edtClave = findViewById(R.id.edtClave)
        edtId = findViewById(R.id.edtId)
        edtImei = findViewById(R.id.edtImei)
        edtAbonado = findViewById(R.id.edtAbonado)
        edtIp = findViewById(R.id.edtIp)
        edtPort = findViewById(R.id.edtPort)
        edtToken = findViewById(R.id.edtToken)
        edtNombre = findViewById(R.id.edtNombre)
        spnServiceMode = findViewById(R.id.spnServiceMode)
        txtModeDescription = findViewById(R.id.txtModeDescription)
        monitoringFieldsContainer = findViewById(R.id.monitoringFieldsContainer)
        abonadoContainer = findViewById(R.id.abonadoContainer)
        panelConfigContainer = findViewById(R.id.panelConfigContainer)
        zoneCustomizationContainer = findViewById(R.id.zoneCustomizationContainer)
        zonesEditorContainer = findViewById(R.id.zonesEditorContainer)
        txtZoneStatus = findViewById(R.id.txtZoneStatus)
        btnPanelConfigTab = findViewById(R.id.btnPanelConfigTab)
        btnZoneCustomizationTab = findViewById(R.id.btnZoneCustomizationTab)
        btnSaveZones = findViewById(R.id.btnSaveZones)
        btnScanQr = findViewById(R.id.btnScanQr)
        txtQrStatus = findViewById(R.id.txtQrStatus)
        val btnGuardar: Button = findViewById(R.id.btnGuardar)

        InteractionFeedback.install(btnPanelConfigTab, FeedbackKind.NAVIGATION)
        InteractionFeedback.install(btnZoneCustomizationTab, FeedbackKind.NAVIGATION)
        InteractionFeedback.install(btnScanQr, FeedbackKind.NAVIGATION)
        InteractionFeedback.install(btnSaveZones, FeedbackKind.CONTROL)
        InteractionFeedback.install(btnGuardar, FeedbackKind.CONTROL)

        btnScanQr.visibility = if (addMode) View.VISIBLE else View.GONE
        txtQrStatus.visibility = if (addMode) View.VISIBLE else View.GONE
        btnScanQr.setOnClickListener { scanEquipmentQr() }

        createZoneEditors()
        showPanelConfigTab()
        btnPanelConfigTab.setOnClickListener { showPanelConfigTab() }
        btnZoneCustomizationTab.isEnabled = !addMode
        btnZoneCustomizationTab.alpha = if (addMode) 0.5f else 1f
        btnZoneCustomizationTab.setOnClickListener {
            if (addMode) {
                Toast.makeText(this, "Guardá primero el panel y luego podrás personalizar sus zonas", Toast.LENGTH_LONG).show()
            } else {
                showZoneCustomizationTab()
            }
        }
        btnSaveZones.setOnClickListener { saveZoneNames() }

        spnServiceMode.adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            SERVICE_MODE_LABELS
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        title.text = if (addMode) "Agregar panel" else "Editar panel"
        edtNombre.setText(Prefs.name(this))

        val initialMode = if (addMode) {
            ServiceMode.SELF_MONITORING
        } else {
            val panel = Prefs.selectedPanel(this)
            originalImei = panel?.imei
            edtPanelName.setText(panel?.panelName.orEmpty())
            edtClave.setText(panel?.clave.orEmpty())
            edtId.setText(panel?.id.orEmpty())
            edtImei.setText(panel?.imei.orEmpty())
            edtAbonado.setText(panel?.abonado.orEmpty())
            edtIp.setText(panel?.ip.orEmpty())
            panel?.port?.takeIf { it != 0 }?.let { edtPort.setText(it.toString()) }
            edtToken.setText(panel?.accessToken.orEmpty())
            panel?.serviceMode ?: ServiceMode.MONITORING
        }
        spnServiceMode.setSelection(positionForMode(initialMode))
        updateMonitoringVisibility(initialMode)
        spnServiceMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateMonitoringVisibility(modeForPosition(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        btnGuardar.setOnClickListener {
            val serviceMode = modeForPosition(spnServiceMode.selectedItemPosition)
            val panelName = edtPanelName.text.toString().trim()
            val clave = edtClave.text.toString().trim()
            val id = edtId.text.toString().trim()
            val imei = edtImei.text.toString().trim()
            val abonado = edtAbonado.text.toString().trim()
            val ip = edtIp.text.toString().trim()
            val portStr = edtPort.text.toString().trim()
            val token = edtToken.text.toString().trim()
            val nombre = edtNombre.text.toString().trim()

            if (TextUtils.isEmpty(panelName) || TextUtils.isEmpty(nombre) ||
                TextUtils.isEmpty(imei) || TextUtils.isEmpty(token)
            ) {
                Toast.makeText(this, "Completá los datos del panel y del servidor", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (serviceMode == ServiceMode.MONITORING &&
                (abonado.isEmpty() || clave.isEmpty() || id.isEmpty() || ip.isEmpty() || portStr.isEmpty())
            ) {
                Toast.makeText(this, "Completá los datos del monitoreo UDP", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val portNum = portStr.toIntOrNull() ?: 0
            if (serviceMode == ServiceMode.MONITORING && portNum !in 1..65535) {
                Toast.makeText(this, "Puerto inválido", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!imei.matches(Regex("\\d{15}"))) {
                Toast.makeText(this, "El IMEI del equipo debe tener 15 dígitos", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val duplicate = Prefs.panels(this).any {
                it.imei == imei && it.imei != originalImei
            }
            if (duplicate) {
                Toast.makeText(this, "Ese panel ya está cargado", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            runCatching {
                Prefs.savePanelConfig(
                    this,
                    panelName,
                    nombre,
                    serviceMode,
                    clave,
                    id,
                    imei,
                    abonado,
                    ip,
                    portNum,
                    token,
                    originalImei
                )
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            Prefs.resetCounter(this)
            PushRegistration.syncCurrentTokenForPanel(this, token, force = true)
            Thread {
                runCatching { AlertApiClient.updateInstallationName(token, nombre) }
            }.start()
            Toast.makeText(this, "Panel guardado", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, PanelsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            finish()
        }
    }

    private fun createZoneEditors() {
        for (zone in 1..16) {
            val label = TextView(this).apply {
                text = "Zona $zone"
                setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.m41_text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(if (zone == 1) 4 else 14), 0, 0)
            }
            val input = EditText(this).apply {
                hint = "Nombre opcional"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 1
                filters = arrayOf(InputFilter.LengthFilter(60))
                setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.m41_text_primary))
                setHintTextColor(ContextCompat.getColor(this@SetupActivity, R.color.m41_text_secondary))
            }
            zonesEditorContainer.addView(label)
            zonesEditorContainer.addView(input)
            zoneInputs[zone] = input
        }
    }

    private fun scanEquipmentQr() {
        val personName = edtNombre.text.toString().trim()
        if (personName.isEmpty()) {
            Toast.makeText(this, "Completá primero el nombre de la persona", Toast.LENGTH_LONG).show()
            edtNombre.requestFocus()
            return
        }
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        txtQrStatus.text = "Abrí la cámara y apuntá a la etiqueta NanoSmart."
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                linkScannedEquipment(barcode.rawValue.orEmpty(), personName)
            }
            .addOnCanceledListener {
                txtQrStatus.text = "Escaneo cancelado. También podés realizar la carga manual."
            }
            .addOnFailureListener { error ->
                txtQrStatus.text = error.message ?: "No se pudo abrir el lector QR. Usá la carga manual."
            }
    }

    private fun linkScannedEquipment(rawValue: String, personName: String) {
        val pairing = QrPairingParser.parse(rawValue)
        if (pairing == null) {
            txtQrStatus.text = "El código escaneado no es una etiqueta válida de NanoSmart."
            Toast.makeText(this, "QR NanoSmart inválido", Toast.LENGTH_LONG).show()
            return
        }

        btnScanQr.isEnabled = false
        txtQrStatus.text = "Vinculando este celular con el equipo…"
        Thread {
            runCatching {
                AlertApiClient.registerInstallation(pairing.imei, pairing.accessKey, personName)
            }
                .onSuccess { registration ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        edtImei.setText(registration.imei)
                        edtToken.setText(registration.accessToken)
                        btnScanQr.isEnabled = true
                        txtQrStatus.text = "Equipo vinculado. IMEI y token cargados automáticamente."
                        Toast.makeText(this, "QR vinculado correctamente", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        btnScanQr.isEnabled = true
                        txtQrStatus.text = error.message ?: "No se pudo vincular el equipo."
                    }
                }
        }.start()
    }

    private fun showPanelConfigTab() {
        panelConfigContainer.visibility = View.VISIBLE
        zoneCustomizationContainer.visibility = View.GONE
        btnPanelConfigTab.setBackgroundResource(R.drawable.bg_tab_selected)
        btnPanelConfigTab.setTextColor(ContextCompat.getColor(this, R.color.m41_red))
        btnZoneCustomizationTab.setBackgroundResource(R.drawable.bg_button_secondary)
        btnZoneCustomizationTab.setTextColor(ContextCompat.getColor(this, R.color.m41_button_text))
    }

    private fun showZoneCustomizationTab() {
        panelConfigContainer.visibility = View.GONE
        zoneCustomizationContainer.visibility = View.VISIBLE
        btnPanelConfigTab.setBackgroundResource(R.drawable.bg_button_secondary)
        btnPanelConfigTab.setTextColor(ContextCompat.getColor(this, R.color.m41_button_text))
        btnZoneCustomizationTab.setBackgroundResource(R.drawable.bg_tab_selected)
        btnZoneCustomizationTab.setTextColor(ContextCompat.getColor(this, R.color.m41_red))
        if (!zonesLoaded) loadZoneNames()
    }

    private fun loadZoneNames() {
        val token = edtToken.text.toString().trim()
        if (token.isEmpty()) {
            txtZoneStatus.text = "Falta el token de este celular para consultar el receptor."
            return
        }
        setZoneRequestInProgress(true, "Consultando nombres guardados en el receptor…")
        Thread {
            runCatching { AlertApiClient.fetchZoneNames(token) }
                .onSuccess { names ->
                    runOnUiThread {
                        applyZoneNames(names)
                        zonesLoaded = true
                        setZoneRequestInProgress(false, "Nombres cargados desde el receptor.")
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        setZoneRequestInProgress(
                            false,
                            error.message ?: "No se pudieron consultar los nombres de zonas."
                        )
                    }
                }
        }.start()
    }

    private fun saveZoneNames() {
        val token = edtToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Falta el token de este celular", Toast.LENGTH_LONG).show()
            return
        }
        val names = zoneInputs.mapValues { (_, input) -> input.text.toString().trim() }
        setZoneRequestInProgress(true, "Guardando para todos los celulares de este panel…")
        Thread {
            runCatching { AlertApiClient.saveZoneNames(token, names) }
                .onSuccess { savedNames ->
                    runOnUiThread {
                        applyZoneNames(savedNames)
                        zonesLoaded = true
                        setZoneRequestInProgress(false, "Guardado en el receptor para todos los usuarios.")
                        Toast.makeText(this, "Nombres de zonas guardados", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        setZoneRequestInProgress(
                            false,
                            error.message ?: "No se pudieron guardar los nombres de zonas."
                        )
                    }
                }
        }.start()
    }

    private fun applyZoneNames(names: Map<Int, String>) {
        zoneInputs.forEach { (zone, input) -> input.setText(names[zone].orEmpty()) }
    }

    private fun setZoneRequestInProgress(inProgress: Boolean, message: String) {
        btnSaveZones.isEnabled = !inProgress
        btnZoneCustomizationTab.isEnabled = !inProgress
        txtZoneStatus.text = message
    }

    private fun updateMonitoringVisibility(mode: ServiceMode) {
        val isMonitoring = mode == ServiceMode.MONITORING
        monitoringFieldsContainer.visibility = if (isMonitoring) View.VISIBLE else View.GONE
        abonadoContainer.visibility = if (isMonitoring) View.VISIBLE else View.GONE
        txtModeDescription.text = if (isMonitoring) {
            "Médica, Pánico e Incendio también se enviarán por UDP al software de monitoreo."
        } else {
            "Los eventos se enviarán solamente al receptor NanoSmart y a tus celulares."
        }
    }

    private fun modeForPosition(position: Int): ServiceMode =
        if (position == 1) ServiceMode.MONITORING else ServiceMode.SELF_MONITORING

    private fun positionForMode(mode: ServiceMode): Int =
        if (mode == ServiceMode.MONITORING) 1 else 0

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ADD_PANEL = "add_panel"
        private val SERVICE_MODE_LABELS = listOf("Automonitoreo", "Monitoreo")
    }
}
