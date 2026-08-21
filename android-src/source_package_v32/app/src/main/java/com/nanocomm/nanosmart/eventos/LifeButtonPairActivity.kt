package com.nanocomm.nanosmart.eventos

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.UUID

class LifeButtonPairActivity : SecureActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, BluetoothDevice>()
    private lateinit var imei: String
    private lateinit var status: TextView
    private lateinit var button: Button
    private var scannerActive = false
    private var validationGatt: BluetoothGatt? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!hasBlePermissions()) {
            Toast.makeText(this, "Se necesitan permisos Bluetooth para vincular el botón", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (!hasLocationPermission()) {
            Toast.makeText(
                this,
                "El botón se puede vincular, pero habilitá Ubicación precisa para enviar la posición en una emergencia",
                Toast.LENGTH_LONG
            ).show()
        }
        startSearch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imei = intent.getStringExtra(EXTRA_IMEI)?.trim().orEmpty()
        if (!imei.matches(Regex("\\d{15}"))) {
            Toast.makeText(this, "Primero cargá un IMEI válido", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(buildContent())
        renderCurrent()
    }

    override fun onDestroy() {
        stopScan()
        runCatching { validationGatt?.disconnect() }
        runCatching { validationGatt?.close() }
        validationGatt = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        root.addView(TextView(this).apply {
            text = "Vincular Botón Vida"
            textSize = 24f
            setTextColor(ContextCompat.getColor(this@LifeButtonPairActivity, R.color.m41_text_primary))
        })
        root.addView(TextView(this).apply {
            text = "La app verificará automáticamente que el dispositivo sea compatible. Para enviar la ubicación durante una emergencia, permití también la ubicación precisa."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(18))
            setTextColor(ContextCompat.getColor(this@LifeButtonPairActivity, R.color.m41_text_secondary))
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
            setTextColor(ContextCompat.getColor(this@LifeButtonPairActivity, R.color.m41_text_primary))
        }
        root.addView(status)
        button = Button(this).apply {
            text = "Buscar botón"
            isAllCaps = false
            setOnClickListener { requestSearch() }
        }
        root.addView(button)
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderCurrent() {
        val config = LifeButtonPrefs.config(this, imei)
        val locationStatus = if (hasLocationPermission()) {
            " · ubicación habilitada"
        } else {
            " · ubicación sin permiso"
        }
        status.text = if (config.deviceAddress.isBlank()) {
            "Todavía no hay un botón vinculado a este panel.$locationStatus"
        } else {
            val connection = if (LifeButtonPrefs.connected(this, imei)) "conectado" else "guardado"
            val battery = LifeButtonPrefs.battery(this, imei)?.let { " · batería $it%" }.orEmpty()
            "Botón $connection · ${masked(config.deviceAddress)}$battery$locationStatus"
        }
        button.text = if (config.deviceAddress.isBlank()) "Buscar botón" else "Cambiar botón"
    }

    private fun requestSearch() {
        if (!hasBlePermissions() || !hasLocationPermission()) {
            val needed = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    needed += Manifest.permission.BLUETOOTH_SCAN
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    needed += Manifest.permission.BLUETOOTH_CONNECT
                }
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            permissionsLauncher.launch(needed.distinct().toTypedArray())
            return
        }
        startSearch()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSearch() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Activá Bluetooth para buscar el botón", Toast.LENGTH_LONG).show()
            return
        }
        found.clear()
        adapter.bondedDevices.orEmpty().forEach { device -> found[device.address] = device }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            showResults()
            return
        }
        scannerActive = true
        button.isEnabled = false
        button.text = "Buscando…"
        status.text = "Buscando dispositivos cercanos…"
        scanner.startScan(scanCallback)
        handler.postDelayed({
            stopScan()
            showResults()
        }, SCAN_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scannerActive) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scannerActive = false
        if (::button.isInitialized) button.isEnabled = true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            found[result.device.address] = result.device
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { found[it.device.address] = it.device }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                stopScan()
                status.text = "No se pudo realizar la búsqueda Bluetooth ($errorCode)."
                renderCurrent()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showResults() {
        button.isEnabled = true
        renderCurrent()
        if (found.isEmpty()) {
            Toast.makeText(this, "No se encontraron dispositivos. Acercá el botón e intentá nuevamente.", Toast.LENGTH_LONG).show()
            return
        }
        val devices = found.values.toList()
        val labels = devices.mapIndexed { index, device ->
            "Dispositivo ${index + 1}\n${masked(device.address)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Seleccionar botón")
            .setItems(labels) { _, which -> validateDevice(devices[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun validateDevice(device: BluetoothDevice) {
        runCatching { validationGatt?.close() }
        status.text = "Verificando compatibilidad del botón…"
        button.isEnabled = false
        var completed = false

        fun fail(message: String) {
            if (completed) return
            completed = true
            runCatching { validationGatt?.disconnect() }
            runCatching { validationGatt?.close() }
            validationGatt = null
            runOnUiThread {
                button.isEnabled = true
                renderCurrent()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { gatt.discoverServices() }.onFailure { fail("No se pudo verificar el botón") }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !completed) {
                    fail("No se pudo conectar con el botón seleccionado")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
                if (completed) return
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    fail("No se pudieron leer las características del botón")
                    return
                }
                val characteristic = gatt.getService(BUTTON_SERVICE_UUID)
                    ?.getCharacteristic(BUTTON_CHARACTERISTIC_UUID)
                val compatible = characteristic != null &&
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (!compatible) {
                    fail("El dispositivo seleccionado no es compatible con Botón Vida")
                    return
                }
                completed = true
                val internalName = runCatching { device.name }.getOrNull().orEmpty()
                LifeButtonPrefs.saveDevice(this@LifeButtonPairActivity, imei, device.address, internalName)
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                validationGatt = null
                runOnUiThread {
                    button.isEnabled = true
                    renderCurrent()
                    Toast.makeText(this@LifeButtonPairActivity, "Botón vinculado correctamente", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    LifeButtonService.start(this@LifeButtonPairActivity)
                }
            }
        }

        validationGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, callback)
        }
        handler.postDelayed({ fail("Tiempo agotado al verificar el botón") }, VALIDATION_TIMEOUT_MS)
    }

    private fun masked(address: String): String {
        val clean = address.trim()
        return if (clean.length <= 8) clean else "••:••:${clean.takeLast(8)}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_IMEI = "life_button_panel_imei"
        private const val SCAN_MS = 8_000L
        private const val VALIDATION_TIMEOUT_MS = 12_000L
        private val BUTTON_SERVICE_UUID = UUID.fromString("ccaf68a3-dd38-4c61-bfd2-9b14027605ea")
        private val BUTTON_CHARACTERISTIC_UUID = UUID.fromString("1f1e4671-b051-4a30-837c-86f3b11cc5ae")
    }
}
