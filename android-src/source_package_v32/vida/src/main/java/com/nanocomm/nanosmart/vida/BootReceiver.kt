package com.nanocomm.nanosmart.vida

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (LifePrefs.load(context).enabled) {
            LifeBleService.start(context)
        }
    }
}
