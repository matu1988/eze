package com.nanocomm.nanosmart.eventos

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MultipanelStressInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun multipanelAndSimulatedPushStress() {
        assertTrue("La prueba debe ejecutarse sobre la variante Demo", DemoMode.enabled)
        val results = JSONArray()

        for (panelCount in listOf(100, 500, 1000)) {
            val panels = panels(panelCount)
            val saveStarted = SystemClock.elapsedRealtime()
            Prefs.replacePanelsForDemo(context, "Prueba de carga", panels)
            val saveMs = SystemClock.elapsedRealtime() - saveStarted

            val launchStarted = SystemClock.elapsedRealtime()
            val scenario = ActivityScenario.launch<PanelsActivity>(
                Intent(context, PanelsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            instrumentation.waitForIdleSync()
            val renderMs = SystemClock.elapsedRealtime() - launchStarted
            var cards = -1
            var visibleCards = -1
            var totalViews = -1
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.panelsList)
                cards = list.adapter?.itemCount ?: -1
                visibleCards = list.childCount
                totalViews = countViews(activity.window.decorView)
            }
            assertEquals(panelCount, cards)

            results.put(
                JSONObject()
                    .put("stage", "panel_list")
                    .put("panels", panelCount)
                    .put("saveMs", saveMs)
                    .put("renderMs", renderMs)
                    .put("cards", cards)
                    .put("visibleCards", visibleCards)
                    .put("totalViews", totalViews)
                    .put("pssKb", Debug.getPss())
                    .put("javaHeapKb", usedJavaHeapKb())
            )
            scenario.close()
            instrumentation.waitForIdleSync()
            Runtime.getRuntime().gc()

            val selectedImei = panels.first().imei
            Prefs.selectPanel(context, selectedImei)
            val mainScenario = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PANEL_IMEI, selectedImei)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            instrumentation.waitForIdleSync()

            val service = attachedMessagingService()
            val pushCount = panelCount
            val pushStarted = SystemClock.elapsedRealtime()
            for (index in 0 until pushCount) {
                service.onMessageReceived(simulatedPush(selectedImei, index))
            }
            instrumentation.waitForIdleSync()
            val pushMs = SystemClock.elapsedRealtime() - pushStarted

            var alertViews = -1
            mainScenario.onActivity { activity ->
                alertViews = activity.findViewById<LinearLayout>(R.id.alertsContainer).childCount
            }
            results.put(
                JSONObject()
                    .put("stage", "simulated_push")
                    .put("panels", panelCount)
                    .put("pushes", pushCount)
                    .put("elapsedMs", pushMs)
                    .put("pushesPerSecond", if (pushMs > 0) pushCount * 1000.0 / pushMs else pushCount)
                    .put("alertContainerChildren", alertViews)
                    .put("pssKb", Debug.getPss())
                    .put("javaHeapKb", usedJavaHeapKb())
            )

            service.onDestroy()
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent("com.nanocomm.nanosmart.eventos.STRESS_DRAIN"))
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
            mainScenario.close()
            instrumentation.waitForIdleSync()
            Runtime.getRuntime().gc()
        }

        val output = File(context.filesDir, "multipanel_stress_results.json")
        output.writeText(results.toString(2))
        assertTrue(output.exists() && output.length() > 0)
    }

    private fun panels(count: Int): List<PanelConfig> = (0 until count).map { index ->
        val imei = String.format(Locale.US, "990%012d", index)
        PanelConfig(
            panelName = "Equipo ${index + 1}",
            serviceMode = ServiceMode.SELF_MONITORING,
            clave = "1234",
            id = String.format(Locale.US, "%02d", (index % 99) + 1),
            imei = imei,
            abonado = (2000 + index).toString(),
            ip = "",
            port = 0,
            accessToken = "TOKEN-SIMULADO-$index"
        )
    }

    private fun simulatedPush(imei: String, index: Int): RemoteMessage {
        val code = listOf("130", "100", "110", "120", "45", "46")[index % 6]
        val isState = code == "45" || code == "46"
        val data = mutableMapOf(
            "type" to if (isState) "PANEL_STATE" else "ALERT",
            "title" to "Aviso simulado",
            "body" to "Prueba $index sin conexión a Firebase",
            "alertId" to (10_000_000L + index).toString(),
            "imei" to imei,
            "eventCode" to code,
            "eventDescription" to "Evento simulado $code",
            "actorName" to "Usuario de prueba"
        )
        if (isState) {
            data["panelStatus"] = if (code == "45") "ARMADO" else "DESARMADO"
            data["actionSource"] = if (index % 2 == 0) "APP" else "TECLADO"
        }
        return RemoteMessage.Builder("simulated-$index")
            .setMessageId("simulated-$index")
            .setData(data)
            .build()
    }

    private fun attachedMessagingService(): NanoSmartMessagingService {
        val service = NanoSmartMessagingService()
        val attach = ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java
        )
        attach.isAccessible = true
        attach.invoke(service, context)
        service.onCreate()
        return service
    }

    private fun countViews(view: View): Int {
        if (view !is ViewGroup) return 1
        var total = 1
        for (index in 0 until view.childCount) total += countViews(view.getChildAt(index))
        return total
    }

    private fun usedJavaHeapKb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L
    }
}
