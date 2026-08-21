package com.nanocomm.nanosmart.eventos

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

abstract class SecureActivity : AppCompatActivity() {

    private var lockOverlay: View? = null
    private var authenticationInProgress = false
    private var securitySetupDialog: AlertDialog? = null

    protected val isSecurityUnlocked: Boolean
        get() = DemoMode.enabled || AppLockState.isUnlocked

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!DemoMode.enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onCreate(savedInstanceState)
        keepContentClearOfSystemNavigation()
    }

    /**
     * Android 15 dibuja las aplicaciones debajo de las barras del sistema.
     * Reservamos el espacio inferior y lateral en un solo lugar para que los
     * controles nunca queden tapados por los botones o gestos del teléfono.
     */
    private fun keepContentClearOfSystemNavigation() {
        val content = findViewById<View>(android.R.id.content)
        val initialLeft = content.paddingLeft
        val initialRight = content.paddingRight
        val initialBottom = content.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val safeArea = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = initialLeft + safeArea.left,
                right = initialRight + safeArea.right,
                bottom = initialBottom + safeArea.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onResume() {
        super.onResume()
        if (DemoMode.enabled) {
            hideLockOverlay()
            return
        }
        if (!AppLockState.isUnlocked) {
            showLockOverlay()
            authenticateAppEntry()
        } else {
            hideLockOverlay()
        }
    }

    protected open fun onSecurityUnlocked() = Unit

    protected fun authenticateSensitiveAction(
        title: String,
        subtitle: String,
        onAuthenticated: () -> Unit
    ) {
        if (DemoMode.enabled) {
            onAuthenticated()
            return
        }
        authenticate(
            title = title,
            subtitle = subtitle,
            appEntry = false,
        ) {
            AppLockState.unlock()
            hideLockOverlay()
            onAuthenticated()
        }
    }

    private fun authenticateAppEntry() {
        authenticate(
            title = "Desbloquear NanoSmart",
            subtitle = "Usá tu huella, rostro o bloqueo del teléfono",
            appEntry = true
        ) {
            AppLockState.unlock()
            hideLockOverlay()
            onSecurityUnlocked()
        }
    }

    private fun authenticate(
        title: String,
        subtitle: String,
        appEntry: Boolean,
        onAuthenticated: () -> Unit
    ) {
        if (authenticationInProgress || isFinishing || isDestroyed) return
        if (!isDeviceSecure()) {
            showSecuritySetupDialog()
            return
        }

        authenticationInProgress = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticationInProgress = false
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationInProgress = false
                    if (!appEntry && errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(this@SecureActivity, errString, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(
                        this@SecureActivity,
                        "No se pudo verificar la identidad",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun isDeviceSecure(): Boolean {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isDeviceSecure
    }

    private fun showSecuritySetupDialog() {
        if (securitySetupDialog?.isShowing == true) return
        securitySetupDialog = AlertDialog.Builder(this)
            .setTitle("Protegé primero el teléfono")
            .setMessage(
                "NanoSmart necesita que configures una huella, reconocimiento facial, " +
                    "PIN, patrón o contraseña en el teléfono."
            )
            .setCancelable(false)
            .setPositiveButton("Configurar") { _, _ ->
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            .setNegativeButton("Salir") { _, _ -> moveTaskToBack(true) }
            .create()
        securitySetupDialog?.show()
    }

    private fun showLockOverlay() {
        if (lockOverlay != null) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(TextView(this@SecureActivity).apply {
                text = "NanoSmart"
                textSize = 30f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@SecureActivity).apply {
                text = "Aplicación protegida"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(22))
            })
            addView(Button(this@SecureActivity).apply {
                text = "Desbloquear"
                isAllCaps = false
                setOnClickListener { authenticateAppEntry() }
            })
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SecureActivity, R.color.m41_red))
            elevation = dp(100).toFloat()
            isClickable = true
            isFocusable = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        val decor = window.decorView as ViewGroup
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        lockOverlay = overlay
    }

    private fun hideLockOverlay() {
        val overlay = lockOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        lockOverlay = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
