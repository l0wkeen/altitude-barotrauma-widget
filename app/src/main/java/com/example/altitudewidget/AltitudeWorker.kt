package com.example.altitudewidget

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class AltitudeWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

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
                withTimeoutOrNull(3000L) { deferred.await() }
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
            Result.success()
        }
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