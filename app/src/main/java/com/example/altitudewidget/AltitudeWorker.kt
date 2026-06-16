package com.example.altitudewidget

// AltitudeWorker는 더 이상 사용되지 않습니다.
// 서비스 시작은 AltitudeWidgetProvider.onUpdate() / onEnabled() 및
// BootReceiver에서 startForegroundService()로 직접 처리합니다.
// 이 파일은 WorkManager 의존성이 build.gradle에 남아 있는 동안
// 컴파일 오류 방지를 위해 빈 stub으로 유지합니다.

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AltitudeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork() = Result.success()
}
