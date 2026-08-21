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
    private lateinit var accessKey: EditText
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
            text = "Climax BL3 · Contact ID 640 · escucha BLE permanente"
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
        accessKey = field(root, "Clave de acceso NanoSmart (NS-....)")
        abonado = field(root, "Abonado (4 caracteres)")
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
        btnPair = Button(this).apply { text = "Buscar y vincular Climax BL3" }
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
        save.setOnClickListener { saveAndActivate() }
        test.setOnClickListener { confirmTestEvent() }
        enabledSwitch.setOnCheckedChangeListener { _, checked -> btnPair.isEnabled = checked }

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
        accessKey.setText(config.accessKey)
        abonado.setText(config.abonado)
        transmitter.setText(config.transmitterId)
        key.setText(config.key)
        ip.setText(config.monitoringIp)
        if (config.monitoringPort > 0) port.setText(config.monitoringPort.toString())
        updateButtonLabel(config)
        btnPair.isEnabled = config.enabled
        if (config.enabled && config.validForService()) LifeBleService.start(this)
    }

    private fun currentConfig(): LifeConfig {
        val stored = LifePrefs.load(this)
        return LifeConfig(
            enabled = enabledSwitch.isChecked,
            personName = person.text.toString().trim(),
            panelName = panel.text.toString().trim(),
            imei = imei.text.toString().trim(),
            accessKey = accessKey.text.toString().trim().uppercase(),
            token = stored.token,
            abonado = abonado.text.toString().trim(),
            transmitterId = transmitter.text.toString().trim(),
            key = key.text.toString().trim(),
            monitoringIp = ip.text.toString().trim(),
            monitoringPort = port.text.toString().trim().toIntOrNull() ?: 0,
            deviceAddress = stored.deviceAddress,
            deviceName = stored.deviceName
        )
    }

    private fun validateForActivation(config: LifeConfig): String? = when {
        config.personName.isBlank() -> "Ingresá el nombre de la persona"
        !config.imei.matches(Regex("\\d{15}")) -> "El IMEI debe tener 15 dígitos"
        config.accessKey.isBlank() -> "Ingresá la clave de acceso NanoSmart"
        config.abonado.length != 4 -> "El abonado debe tener 4 caracteres"
        config.transmitterId.isBlank() -> "Ingresá el ID / transmisor"
        config.key.isBlank() -> "Ingresá la clave del equipo"
        config.monitoringIp.isBlank() -> "Ingresá la IP de monitoreo"
        config.monitoringPort !in 1..65535 -> "Puerto de monitoreo inválido"
        config.deviceAddress.isBlank() -> "Vinculá primero el Climax BL3"
        else -> null
    }

    private fun saveAndActivate() {
        var config = currentConfig()
        if (!config.enabled) {
            LifePrefs.save(this, config)
            stopService(Intent(this, LifeBleService::class.java))
            LifePrefs.setConnection(this, false)
            Toast.makeText(this, "Botón Vida desactivado", Toast.LENGTH_SHORT).show()
            return
        }
        validateForActivation(config)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        LifePrefs.save(this, config)
        LifePrefs.setServerState(this, "Registrando credencial Botón Vida…")
        Thread {
            runCatching {
                val token = LifeRegistration.register(config)
                LifePrefs.setToken(this, token)
                config = config.copy(token = token)
                LifePrefs.save(this, config)
                LifePrefs.setServerState(this, "Conectado al servidor")
                runOnUiThread {
                    LifeBleService.start(this)
                    Toast.makeText(this, "Botón Vida activado", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                LifePrefs.setServerState(this, "Error de registro")
                runOnUiThread {
                    Toast.makeText(this, "No se pudo registrar Botón Vida: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Activá Bluetooth para buscar el botón", Toast.LENGTH_LONG).show()
            return
        }
        foundDevices.clear()
        adapter.bondedDevices
            .filter { runCatching { it.name.orEmpty().startsWith("BL3", ignoreCase = true) }.getOrDefault(false) }
            .forEach { foundDevices[it.address] = it }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            showScanResults()
            return
        }
        btnPair.isEnabled = false
        btnPair.text = "Buscando Climax BL3…"
        scanner.startScan(scanCallback)
        handler.postDelayed({
            runCatching { scanner.stopScan(scanCallback) }
            btnPair.isEnabled = true
            btnPair.text = if (LifePrefs.load(this).deviceAddress.isBlank()) "Buscar y vincular Climax BL3" else "Cambiar botón"
            showScanResults()
        }, SCAN_MS)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = runCatching { result.device.name.orEmpty() }.getOrDefault("")
            if (name.startsWith("BL3", ignoreCase = true)) foundDevices[result.device.address] = result.device
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                btnPair.isEnabled = true
                btnPair.text = "Buscar y vincular Climax BL3"
                Toast.makeText(this@MainActivity, "Falló el escaneo BLE ($errorCode)", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScanResults() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "No se encontró ningún Climax BL3", Toast.LENGTH_LONG).show()
            return
        }
        val devices = foundDevices.values.toList()
        val labels = devices.map { device ->
            val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Climax BL3" }
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
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Climax BL3" }
        val updated = current.copy(deviceAddress = device.address, deviceName = name, token = "")
        LifePrefs.save(this, updated)
        updateButtonLabel(updated)
        btnPair.text = "Cambiar botón"
        Toast.makeText(this, "BL3 vinculado: $name. Guardá para activar.", Toast.LENGTH_LONG).show()
    }

    private fun confirmTestEvent() {
        val config = currentConfig().copy(token = LifePrefs.load(this).token)
        if (!config.validForService()) {
            Toast.makeText(this, "Guardá y activá una configuración completa antes de probar", Toast.LENGTH_LONG).show()
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
        buttonLabel.text = if (config.deviceAddress.isBlank()) "Sin botón vinculado"
        else "${config.deviceName.ifBlank { "Climax BL3" }} · ${config.deviceAddress}"
        btnPair.text = if (config.deviceAddress.isBlank()) "Buscar y vincular Climax BL3" else "Cambiar botón"
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
        val credential = if (LifePrefs.load(this).token.isNotBlank()) "Credencial Botón Vida OK" else "Sin credencial Botón Vida"
        status.text = "Estado del botón: $connected\nBatería: $battery\nÚltima pulsación: $lastPress\nServidor: ${LifePrefs.serverState(this)}\n$credential\nPendientes de envío: $pending"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCAN_MS = 8000L
    }
}
