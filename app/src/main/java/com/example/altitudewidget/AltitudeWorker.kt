package com.example.altitudewidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AltitudeWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        if (pressureSensor == null) {
            context.getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, false).apply()
            AltitudeWidgetProvider.updateWidgets(context)
            return Result.success()
        }

        val deferred = CompletableDeferred<Float>()
        val mainHandler = Handler(Looper.getMainLooper())

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!deferred.isCompleted) deferred.complete(event.values[0])
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        mainHandler.post {
            sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
        }

        val pressure = try {
            withTimeoutOrNull(8000L) { deferred.await() }
        } finally {
            sensorManager.unregisterListener(listener)
        }

        if (pressure == null) return Result.retry()

        val altitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure
        )

        val prefs = context.getSharedPreferences(
            AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE
        )
        val prevAltitude = prefs.getFloat(
            AltitudeWidgetProvider.KEY_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE
        )
        val prevAccumulated = prefs.getFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, 0f)

        val immediateChange = if (prevAltitude != AltitudeWidgetProvider.INVALID_ALTITUDE)
            altitude - prevAltitude else 0f
        val accumulated = prevAccumulated + immediateChange

        prefs.edit()
            .putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, true)
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE, altitude)
            .putFloat(AltitudeWidgetProvider.KEY_PREV_ALTITUDE, prevAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, accumulated)
            .apply()

        AltitudeWidgetProvider.updateWidgets(context)
        sendAlertNotificationIfNeeded(context, accumulated)

        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "altitude_measuring"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "고도 측정 중", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("고도 측정 중...")
            .setOngoing(true)
            .build()
        return ForegroundInfo(1000, notification)
    }

    private fun sendAlertNotificationIfNeeded(context: Context, accumulated: Float) {
        val absChange = abs(accumulated)
        if (absChange < 15f) return

        val channelId = "altitude_alert"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "고도 변화 알림", NotificationManager.IMPORTANCE_HIGH)
        )

        val (title, body) = when {
            absChange >= 50f -> Pair("🚨 귀 먹먹함 심각", "발살바법을 시도하세요 (코 막고 코 풀기)")
            absChange >= 30f -> Pair("⚠️ 귀 먹먹함 주의", "하품하거나 침을 삼켜보세요")
            else             -> Pair("💧 귀 압력 변화 감지", "물을 마시거나 하품해 보세요")
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(1001, notification)
    }

    companion object {
        private const val WORK_TAG = "altitude_periodic_work"

        fun schedulePeriodicWork(context: Context) {
            // PeriodicWorkRequest는 setExpedited 불가 → 제거
            val request = PeriodicWorkRequestBuilder<AltitudeWorker>(15, TimeUnit.MINUTES)
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
