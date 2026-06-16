package com.example.altitudewidget

import android.content.Context
import android.content.Intent
import androidx.work.*

/**
 * WorkManager Worker: AltitudeService를 시작하는 역할만 담당
 *
 * - 기존 AlarmManager 15분 반복 예약 제거
 * - 모든 측정·갱신 주기는 AltitudeService 내부(3초)에서 관리
 */
class AltitudeWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        startService(context)
        return Result.success()
    }

    companion object {
        private const val WORK_TAG = "altitude_service_starter"

        fun startService(context: Context) {
            val intent = Intent(context, AltitudeService::class.java)
            context.startForegroundService(intent)
        }

        fun schedulePeriodicWork(context: Context) {
            // AlarmManager 반복 제거: AltitudeService가 START_STICKY로 지속 실행됨
            // 위젯 활성화 시 즉시 서비스 시작만 수행
            startService(context)
        }

        fun cancelWork(context: Context) {
            // 위젯이 모두 제거되면 서비스 중지
            val intent = Intent(context, AltitudeService::class.java)
            context.stopService(intent)
        }
    }
}
