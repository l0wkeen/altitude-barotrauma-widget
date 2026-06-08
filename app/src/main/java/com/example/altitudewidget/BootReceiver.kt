package com.example.altitudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 기기 재부팅 후 WorkManager 주기적 작업 재예약
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AltitudeWorker.schedulePeriodicWork(context)
        }
    }
}
