package com.example.altitudewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 기압 센서로 고도를 측정하고 SharedPreferences에 저장하는 Foreground Service
 *
 * - TYPE_PRESSURE 센서로 기압(hPa) 측정
 * - SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
 *   로 표준대기압 기준 해발고도 계산
 * - 30초마다 새 고도를 저장하고 위젯 갱신
 */
class AltitudeService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // 위젯 갱신 주기: 30초
    private val UPDATE_INTERVAL_MS = 30_000L

    private var lastSavedAltitude: Float? = null

    companion object {
        const val CHANNEL_ID = "altitude_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("센서 시작 중..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 센서 등록
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: run {
            // 기압 센서 없으면 서비스 종료
            stopSelf()
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_PRESSURE) return
        val pressureHpa = event.values[0]
        // 표준대기압(1013.25 hPa) 기준 해발고도 계산
        val currentAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            pressureHpa
        )
        saveAndUpdateWidget(currentAltitude)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 사용 안 함
    }

    private fun saveAndUpdateWidget(currentAltitude: Float) {
        val prefs = getSharedPreferences(
            AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE
        )

        // 이전 고도를 현재와 교체저장
        val prevAltitude = if (prefs.contains(AltitudeWidgetProvider.KEY_CURRENT_ALTITUDE))
            prefs.getFloat(AltitudeWidgetProvider.KEY_CURRENT_ALTITUDE, 0f)
        else null

        prefs.edit().apply {
            prevAltitude?.let { putFloat(AltitudeWidgetProvider.KEY_PREV_ALTITUDE, it) }
            putFloat(AltitudeWidgetProvider.KEY_CURRENT_ALTITUDE, currentAltitude)
            apply()
        }

        // 알림 갱신
        val change = if (prevAltitude != null) currentAltitude - prevAltitude else null
        val msg = AltitudeWidgetProvider.getActionRecommendation(change)
        updateForegroundNotification("고도: ${"%,.0f".format(currentAltitude)}m  $msg")

        // 위젯 UI 갱신
        AltitudeWidgetProvider.updateAllWidgets(this)
    }

    // 알림 채널 생성 (Android 8.0+)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "고도 모니터링",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ear Barotrauma 예방을 위한 고도 모니터링 알림"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Altitude Widget")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
