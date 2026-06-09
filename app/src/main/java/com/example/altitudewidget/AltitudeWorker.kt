package com.example.altitudewidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AltitudeWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

            // Wakeup 센서 우선, 없으면 일반 센서로 fallback
            val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

            if (pressureSensor == null) {
                context.getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, false).apply()
                AltitudeWidgetProvider.updateWidgets(context)
                return@withContext Result.success()
            }

            val deferred = CompletableDeferred<Float>()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!deferred.isCompleted) deferred.complete(event.values[0])
                }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }

            sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)

            val pressure = try {
                withTimeoutOrNull(5000L) { deferred.await() }
            } finally {
                sensorManager.unregisterListener(listener)
            }

            if (pressure == null) return@withContext Result.retry()

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

            // 알림 발송
            sendNotificationIfNeeded(context, accumulated)

            Result.success()
        }
    }

    private fun sendNotificationIfNeeded(context: Context, accumulated: Float) {
        val absChange = abs(accumulated)
        if (absChange < 15f) return  // 15m 미만이면 알림 없음

        val channelId = "altitude_alert"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId, "고도 변화 알림", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "귀 압력 변화 경고" }
        nm.createNotificationChannel(channel)

        val (title, body) = when {
            absChange >= 50f -> Pair("🚨 귀 먹먹함 심각", "발살바법을 시도하세요 (코 막고 코 풀기)")
            absChange >= 30f -> Pair("⚠️ 귀 먹먹함 주의", "하품하거나 침을 삼켜보세요")
            else             -> Pair("💧 귀 압력 변화 감지", "물을 마시거나 하품해 보세요")
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
