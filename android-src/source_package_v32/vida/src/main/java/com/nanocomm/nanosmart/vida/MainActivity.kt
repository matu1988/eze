package com.nanocomm.nanosmart.vida

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var enabledSwitch: Switch
    private lateinit var person: EditText
    private lateinit var panel: EditText
    private lateinit var imei: EditText
    private lateinit var token: EditText
    private lateinit var abonado: EditText
    private lateinit var transmitter: EditText
    private lateinit var key: EditText
    private lateinit var ip: EditText
    private lateinit var port: EditText
    private lateinit var buttonLabel: TextView
    private lateinit var status: TextView
    private lateinit var btnPair: Button
    private val foundDevices = linkedMapOf<String, BluetoothDevice>()
    private var pendingScan = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pendingScan) {
            pendingScan = false
            if (hasBlePermissions()) startBleScan()
        }
        if (LifePrefs.load(this).enabled) LifeBleService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NanoSmart Botón Vida"
        setContentView(buildContent())
        loadConfig()
        requestBasePermissions()
        handler.post(statusPoller)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
        }
        root.addView(TextView(this).apply {
            text = "NanoSmart Botón Vida"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Evento Contact ID 640 · escucha BLE permanente"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(18))
        })

        enabledSwitch = Switch(this).apply {
            text = "Botón Vida habilitado"
            textSize = 17f
        }
        root.addView(enabledSwitch)

        person = field(root, "Nombre de la persona adulta")
        panel = field(root, "Nombre del domicilio o panel")
        imei = field(root, "IMEI del panel (15 dígitos)", InputType.TYPE_CLASS_NUMBER)
        token = field(root, "Token NanoSmart", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        abonado = field(root, "Abonado")
        transmitter = field(root, "ID / transmisor")
        key = field(root, "Clave del equipo")
        ip = field(root, "IP de monitoreo")
        port = field(root, "Puerto de monitoreo", InputType.TYPE_CLASS_NUMBER)

        root.addView(TextView(this).apply {
            text = "Vinculación Bluetooth"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(6))
        })
        buttonLabel = TextView(this).apply {
            text = "Sin botón vinculado"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(buttonLabel)
        btnPair = Button(this).apply { text = "Buscar y vincular botón" }
        root.addView(btnPair)

        val save = Button(this).apply { text = "Guardar y activar" }
        root.addView(save)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(20), 0, dp(10))
        }
        root.addView(status)

        val test = Button(this).apply { text = "Prueba real de evento 640" }
        root.addView(test)

        btnPair.setOnClickListener { requestScan() }
        save.setOnClickListener { saveConfig() }
        test.setOnClickListener { confirmTestEvent() }
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            btnPair.isEnabled = checked
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun field(parent: LinearLayout, label: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        parent.addView(TextView(this).apply {
            text = label
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, 0)
        })
        return EditText(this).apply {
            hint = label
            this.inputType = inputType
            maxLines = 1
            parent.addView(this)
        }
    }

    private fun loadConfig() {
        val config = LifePrefs.load(this)
        enabledSwitch.isChecked = config.enabled
        person.setText(config.personName)
        panel.setText(config.panelName)
        imei.setText(config.imei)
        token.setText(config.token)
        abonado.setText(config.abonado)
        transmitter.setText(config.transmitterId)
        key.setText(config.key)
        ip.setText(config.monitoringIp)
        if (config.monitoringPort > 0) port.setText(config.monitoringPort.toString())
        updateButtonLabel(config)
        btnPair.isEnabled = config.enabled
        if (config.enabled && config.validForService()) LifeBleService.start(this)
    }

    private fun currentConfig(): LifeConfig = LifeConfig(
        enabled = enabledSwitch.isChecked,
        personName = person.text.toString().trim(),
        panelName = panel.text.toString().trim(),
        imei = imei.text.toString().trim(),
        token = token.text.toString().trim(),
        abonado = abonado.text.toString().trim(),
        transmitterId = transmitter.text.toString().trim(),
        key = key.text.toString().trim(),
        monitoringIp = ip.text.toString().trim(),
        monitoringPort = port.text.toString().trim().toIntOrNull() ?: 0,
        deviceAddress = LifePrefs.load(this).deviceAddress,
        deviceName = LifePrefs.load(this).deviceName
    )

    private fun saveConfig() {
        val config = currentConfig()
        if (config.enabled) {
            val problem = when {
                config.personName.isBlank() -> "Ingresá el nombre de la persona"
                !config.imei.matches(Regex("\\d{15}")) -> "El IMEI debe tener 15 dígitos"
                config.token.isBlank() -> "Ingresá el token NanoSmart"
                config.abonado.isBlank() -> "Ingresá el abonado"
                config.transmitterId.isBlank() -> "Ingresá el ID / transmisor"
                config.key.isBlank() -> "Ingresá la clave del equipo"
                config.monitoringIp.isBlank() -> "Ingresá la IP de monitoreo"
                config.monitoringPort !in 1..65535 -> "Puerto de monitoreo inválido"
                config.deviceAddress.isBlank() -> "Vinculá primero el botón Bluetooth"
                else -> null
            }
            if (problem != null) {
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                return
            }
        }
        LifePrefs.save(this, config)
        if (config.enabled) {
            LifeBleService.start(this)
            Toast.makeText(this, "Botón Vida activado", Toast.LENGTH_SHORT).show()
        } else {
            stopService(Intent(this, LifeBleService::class.java))
            LifePrefs.setConnection(this, false)
            Toast.makeText(this, "Botón Vida desactivado", Toast.LENGTH_SHORT).show()
        }
        updateButtonLabel(config)
    }

    private fun requestBasePermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestScan() {
        if (!enabledSwitch.isChecked) return
        if (!hasBlePermissions()) {
            pendingScan = true
            requestBasePermissions()
            return
        }
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Activá Bluetooth para buscar el botón", Toast.LENGTH_LONG).show()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "No se pudo iniciar el escaneo BLE", Toast.LENGTH_LONG).show()
            return
        }
        foundDevices.clear()
        btnPair.isEnabled = false
        btnPair.text = "Buscando…"
        scanner.startScan(scanCallback)
        handler.postDelayed({
            runCatching { scanner.stopScan(scanCallback) }
            btnPair.isEnabled = true
            btnPair.text = if (LifePrefs.load(this).deviceAddress.isBlank()) "Buscar y vincular botón" else "Cambiar botón"
            showScanResults()
        }, SCAN_MS)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            foundDevices[result.device.address] = result.device
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                btnPair.isEnabled = true
                btnPair.text = "Buscar y vincular botón"
                Toast.makeText(this@MainActivity, "Falló el escaneo BLE ($errorCode)", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScanResults() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "No se encontraron dispositivos BLE", Toast.LENGTH_LONG).show()
            return
        }
        val devices = foundDevices.values.toList()
        val labels = devices.map { device ->
            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Dispositivo BLE" }
            "$name\n${device.address}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar Botón Vida")
            .setItems(labels) { _, which -> pairDevice(devices[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun pairDevice(device: BluetoothDevice) {
        val current = currentConfig()
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Botón Vida" }
        val updated = current.copy(deviceAddress = device.address, deviceName = name)
        LifePrefs.save(this, updated)
        updateButtonLabel(updated)
        btnPair.text = "Cambiar botón"
        if (updated.enabled && updated.validForService()) LifeBleService.start(this)
        Toast.makeText(this, "Botón vinculado: $name", Toast.LENGTH_SHORT).show()
    }

    private fun confirmTestEvent() {
        val config = currentConfig()
        if (!config.validForService()) {
            Toast.makeText(this, "Guardá una configuración completa antes de probar", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Prueba real 640")
            .setMessage("Esta prueba genera un evento real Botón Vida en monitoreo. ¿Continuar?")
            .setPositiveButton("Enviar") { _, _ ->
                LifePrefs.enqueue(this, LifeSender.newEvent(this, config, LifePrefs.battery(this)))
                LifeBleService.start(this)
                Toast.makeText(this, "Evento 640 en cola de envío", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateButtonLabel(config: LifeConfig) {
        buttonLabel.text = if (config.deviceAddress.isBlank()) {
            "Sin botón vinculado"
        } else {
            "${config.deviceName.ifBlank { "Botón Vida" }} · ${config.deviceAddress}"
        }
        btnPair.text = if (config.deviceAddress.isBlank()) "Buscar y vincular botón" else "Cambiar botón"
    }

    private val statusPoller = object : Runnable {
        override fun run() {
            if (::status.isInitialized) renderStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun renderStatus() {
        val connected = if (LifePrefs.connected(this)) "Conectado" else "Desconectado"
        val battery = LifePrefs.battery(this)?.let { "$it%" } ?: "No disponible"
        val lastPress = LifePrefs.lastPress(this).takeIf { it > 0L }?.let {
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(it))
        } ?: "Sin pulsaciones"
        val pending = LifePrefs.queue(this).size
        status.text = "Estado del botón: $connected\nBatería: $battery\nÚltima pulsación: $lastPress\nServidor: ${LifePrefs.serverState(this)}\nPendientes de envío: $pending"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCAN_MS = 8000L
    }
}
