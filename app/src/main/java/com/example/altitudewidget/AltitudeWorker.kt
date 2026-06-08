package com.example.altitudewidget

import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker: AltitudeService 주기적 재시작 보장
 * Android가 메모리 부족으로 서비스를 죽여도
 * WorkManager가 15분마다 서비스를 다시 깨웁니다.
 */
class AltitudeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.startService(
            Intent(applicationContext, AltitudeService::class.java)
        )
        return Result.success()
    }

    companion object {
        private const val WORK_TAG = "altitude_periodic_work"

        fun schedulePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<AltitudeWorker>(
                15, TimeUnit.MINUTES
            )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        }
    }
}
