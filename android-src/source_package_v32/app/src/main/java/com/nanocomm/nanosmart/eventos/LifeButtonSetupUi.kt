package com.nanocomm.nanosmart.eventos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

object LifeButtonSetupUi {
    private data class Controls(
        val toggle: SwitchCompat,
        val pairButton: Button,
        val status: TextView,
        val imeiInput: EditText
    )

    private val controls = WeakHashMap<Activity, Controls>()

    fun install(activity: Activity) {
        if (activity !is SetupActivity || DemoMode.enabled) return
        if (controls.containsKey(activity)) return
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed || controls.containsKey(activity)) return@post
            val container = activity.findViewById<LinearLayout>(R.id.panelConfigContainer) ?: return@post
            val imeiInput = activity.findViewById<EditText>(R.id.edtImei) ?: return@post

            val section = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 18), 0, 0)
            }
            section.addView(View(activity).apply {
                setBackgroundColor(ContextCompat.getColor(activity, R.color.m41_grey_chip))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1)))
            section.addView(TextView(activity).apply {
                text = "Botón Vida"
                textSize = 17f
                setPadding(0, dp(activity, 16), 0, dp(activity, 4))
                setTextColor(ContextCompat.getColor(activity, R.color.m41_text_primary))
            })
            section.addView(TextView(activity).apply {
                text = "Permite enviar un pedido de ayuda desde un botón Bluetooth asociado a este panel."
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.m41_text_secondary))
            })

            val toggle = SwitchCompat(activity).apply {
                text = "Habilitar Botón Vida"
                isAllCaps = false
                setPadding(0, dp(activity, 10), 0, dp(activity, 6))
                setTextColor(ContextCompat.getColor(activity, R.color.m41_text_primary))
            }
            section.addView(toggle)

            val pairButton = Button(activity).apply {
                text = "Buscar/Vincular botón"
                isAllCaps = false
                visibility = View.GONE
            }
            section.addView(pairButton)

            val status = TextView(activity).apply {
                textSize = 12f
                setPadding(0, dp(activity, 8), 0, 0)
                setTextColor(ContextCompat.getColor(activity, R.color.m41_text_secondary))
                visibility = View.GONE
            }
            section.addView(status)

            val saveButton = activity.findViewById<Button>(R.id.btnGuardar)
            val saveIndex = container.indexOfChild(saveButton).takeIf { it >= 0 } ?: container.childCount
            container.addView(section, saveIndex)

            val c = Controls(toggle, pairButton, status, imeiInput)
            controls[activity] = c
            refresh(activity)

            toggle.setOnCheckedChangeListener { _, checked ->
                val imei = imeiInput.text.toString().trim()
                if (checked && !imei.matches(Regex("\\d{15}"))) {
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = false
                    toggle.setOnCheckedChangeListener { _, value -> handleToggle(activity, c, value) }
                    Toast.makeText(activity, "Cargá primero el IMEI del panel", Toast.LENGTH_LONG).show()
                    imeiInput.requestFocus()
                    return@setOnCheckedChangeListener
                }
                handleToggle(activity, c, checked)
            }

            pairButton.setOnClickListener {
                val imei = imeiInput.text.toString().trim()
                if (!imei.matches(Regex("\\d{15}"))) {
                    Toast.makeText(activity, "Cargá primero el IMEI del panel", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                activity.startActivity(
                    Intent(activity, LifeButtonPairActivity::class.java)
                        .putExtra(LifeButtonPairActivity.EXTRA_IMEI, imei)
                )
            }

            imeiInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { refresh(activity) }
            })
        }
    }

    private fun handleToggle(activity: Activity, controls: Controls, checked: Boolean) {
        val imei = controls.imeiInput.text.toString().trim()
        if (!imei.matches(Regex("\\d{15}"))) return
        LifeButtonPrefs.setEnabled(activity, imei, checked)
        requestNeededPermissions(activity, checked)
        controls.pairButton.visibility = if (checked) View.VISIBLE else View.GONE
        controls.status.visibility = if (checked) View.VISIBLE else View.GONE
        if (checked) LifeButtonService.start(activity) else LifeButtonService.stopIfUnused(activity)
        refresh(activity)
    }

    fun refresh(activity: Activity) {
        val c = controls[activity] ?: return
        val imei = c.imeiInput.text.toString().trim()
        if (!imei.matches(Regex("\\d{15}"))) {
            c.toggle.setOnCheckedChangeListener(null)
            c.toggle.isChecked = false
            c.pairButton.visibility = View.GONE
            c.status.visibility = View.GONE
            installToggleListener(activity, c)
            return
        }
        val config = LifeButtonPrefs.config(activity, imei)
        c.toggle.setOnCheckedChangeListener(null)
        c.toggle.isChecked = config.enabled
        installToggleListener(activity, c)
        c.pairButton.visibility = if (config.enabled) View.VISIBLE else View.GONE
        c.status.visibility = if (config.enabled) View.VISIBLE else View.GONE
        c.pairButton.text = if (config.deviceAddress.isBlank()) "Buscar/Vincular botón" else "Cambiar botón"
        if (config.enabled) {
            val connection = when {
                config.deviceAddress.isBlank() -> "Sin botón vinculado"
                LifeButtonPrefs.connected(activity, imei) -> "Botón conectado"
                else -> "Botón guardado · desconectado"
            }
            val battery = LifeButtonPrefs.battery(activity, imei)?.let { " · batería $it%" }.orEmpty()
            val lastPress = LifeButtonPrefs.lastPress(activity, imei).takeIf { it > 0L }?.let {
                "\nÚltima pulsación: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(it))}"
            }.orEmpty()
            c.status.text = "$connection$battery$lastPress\n${LifeButtonPrefs.serverState(activity, imei)}"
        }
    }

    private fun installToggleListener(activity: Activity, c: Controls) {
        c.toggle.setOnCheckedChangeListener { _, checked ->
            val imei = c.imeiInput.text.toString().trim()
            if (checked && !imei.matches(Regex("\\d{15}"))) {
                c.toggle.setOnCheckedChangeListener(null)
                c.toggle.isChecked = false
                installToggleListener(activity, c)
                Toast.makeText(activity, "Cargá primero el IMEI del panel", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            handleToggle(activity, c, checked)
        }
    }

    private fun requestNeededPermissions(activity: Activity, enabled: Boolean) {
        if (!enabled) return
        val requested = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requested += Manifest.permission.BLUETOOTH_SCAN
            requested += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requested += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = requested.distinct().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private const val REQUEST_PERMISSIONS = 1640
}
