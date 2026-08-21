package com.nanocomm.nanosmart.eventos

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LifeButtonBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (DemoMode.enabled || LifeButtonPrefs.enabledConfigs(context).isEmpty()) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            BluetoothAdapter.ACTION_STATE_CHANGED -> runCatching { LifeButtonService.start(context) }
        }
    }
}
