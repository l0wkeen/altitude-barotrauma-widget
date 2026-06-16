package com.example.altitudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager 브로드캐스트 수신 → AltitudeService 시작 트리거
 *
 * 기존에 이 Receiver에서 직접 측정·누적 계산을 하던 로직을 제거하고,
 * 모든 측정·갱신은 AltitudeService(3초 슬라이딩 윈도우)에서만 처리한다.
 */
class AltitudeMeasureReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MEASURE = "com.example.altitudewidget.ACTION_MEASURE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MEASURE) return

        // AltitudeService가 아직 실행 중이 아니라면 시작
        val serviceIntent = Intent(context, AltitudeService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
