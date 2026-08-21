package com.nanocomm.nanosmart.eventos

import android.app.Activity
import android.app.Application
import android.os.Bundle

class NanoSmartApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DemoMode.prepare(this)
        NotificationChannels.ensureAlertChannel(this)
        NotificationChannels.ensureAudibleAlarmChannel(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasForeground = AppVisibility.isForeground
                AppVisibility.activityStarted()
                if (!wasForeground) AppLockState.appEnteredForeground()
                AudibleAlarmService.stop(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                AppVisibility.activityStopped()
                if (!AppVisibility.isForeground) AppLockState.appEnteredBackground()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (!DemoMode.enabled && Prefs.isConfigured(this)) {
            PushRegistration.syncCurrentToken(this)
        }
    }
}
