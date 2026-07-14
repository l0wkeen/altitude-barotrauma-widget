package com.example.altitudewidget

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 귀 압력 경고 알림의 액션 버튼("약간/심하게 먹먹해요")을 눌렀을 때
 * 앱을 열지 않고 바로 증상을 기록하기 위한 리시버.
 *
 * 알림이 뜬 시점의 고도/변화량을 그대로 사용하므로, 실제 증상이 느껴진
 * 순간과 가장 가까운 데이터로 개인화 임계값을 계산할 수 있다.
 */
class SymptomLogReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_LOG_SYMPTOM = "com.example.altitudewidget.ACTION_LOG_SYMPTOM"
        const val EXTRA_SYMPTOM_LEVEL = "symptom_level"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_ACCUMULATED_CHANGE = "accumulated_change"
        const val EXTRA_IMMEDIATE_CHANGE = "immediate_change"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_SYMPTOM) return
        val altitude = intent.getFloatExtra(EXTRA_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE)
        if (altitude == AltitudeWidgetProvider.INVALID_ALTITUDE) return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(notificationId)
        }
        Toast.makeText(context, R.string.symptom_saved_toast, Toast.LENGTH_SHORT).show()

        val logEntry = EarLogData(
            altitude = altitude,
            accumulatedChange = intent.getFloatExtra(EXTRA_ACCUMULATED_CHANGE, 0f),
            immediateChange = intent.getFloatExtra(EXTRA_IMMEDIATE_CHANGE, 0f),
            symptomLevel = intent.getIntExtra(EXTRA_SYMPTOM_LEVEL, EarLogData.SYMPTOM_NONE),
            triggeredByAlert = true
        )
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            try {
                EarLogRepository.save(appContext, logEntry)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
