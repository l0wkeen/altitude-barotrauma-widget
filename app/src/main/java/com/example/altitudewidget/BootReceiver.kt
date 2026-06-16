package com.example.altitudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 기기 재부팅 후 AltitudeService 재시작
 * BOOT_COMPLETED는 시스템 브로드캐스트 컨텍스트 → startForegroundService 직접 호출 가능
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, AltitudeService::class.java))
        }
    }
}
