package com.example.altitudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

class AltitudeMeasureReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MEASURE = "com.example.altitudewidget.ACTION_MEASURE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MEASURE) return

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        if (pressureSensor == null) {
            context.getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, false).apply()
            AltitudeWidgetProvider.updateWidgets(context)
            return
        }

        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sensorManager.unregisterListener(this)

                val pressure = event.values[0]
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
                sendAlertIfNeeded(context, accumulated)
                pending.finish()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        handler.post {
            sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }

        // 10초 후 타임아웃
        handler.postDelayed({
            sensorManager.unregisterListener(listener)
            pending.finish()
        }, 10000L)
    }

    private fun sendAlertIfNeeded(context: Context, accumulated: Float) {
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
}
