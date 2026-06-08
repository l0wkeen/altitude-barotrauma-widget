package com.example.altitudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 기기 재부팅 후 WorkManager 주기적 작업 재예약
 * WorkManager는 재부팅 후 자체적으로 복구되지만
 * 명시적으로 재예약해 누락을 방지합니다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AltitudeWorker.schedulePeriodicWork(context)
        }
    }
}
