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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 기압 센서로 고도를 측정하고 SharedPreferences에 저장하는 Foreground Service
 *
 * - TYPE_PRESSURE 센서로 기압(hPa) 측정
 * - SensorManager.getAltitude()로 해발고도 계산
 * - 이동평균 5개 샘플로 노이즈 제거
 * - 3초 간격으로 위젯 갱신
 * - 1분 슬라이딩 윈도우로 누적 변화량 계산
 */
class AltitudeService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "altitude_channel"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 3_000L       // 3초 즈각 갱신
        private const val WINDOW_DURATION_MS = 60_000L      // 1분 누적 윈도우
        private const val MOVING_AVG_SIZE = 5
    }

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // 이동평균 버퍼
    private val altitudeBuffer = ArrayDeque<Float>(MOVING_AVG_SIZE)

    // 1분 슬라이딩 윈도우
    private val altitudeHistory = ArrayDeque<Pair<Long, Float>>()

    private var latestSmoothedAltitude = AltitudeWidgetProvider.INVALID_ALTITUDE

    // 3초마다 위젯 갱신 Runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            saveAndUpdate()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        if (pressureSensor == null) {
            getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, false)
                .apply()
            AltitudeWidgetProvider.updateWidgets(this)
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        if (pressureSensor != null) {
            sensorManager.unregisterListener(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ============ 센서 콜백 ============

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return

        val pressureHpa = event.values[0]
        val rawAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa)

        // 이동평균 적용
        if (altitudeBuffer.size >= MOVING_AVG_SIZE) altitudeBuffer.removeFirst()
        altitudeBuffer.addLast(rawAltitude)
        latestSmoothedAltitude = altitudeBuffer.average().toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ============ 데이터 저장 & 위젯 갱신 ============

    private fun saveAndUpdate() {
        if (latestSmoothedAltitude == AltitudeWidgetProvider.INVALID_ALTITUDE) return

        val prefs = getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        val prevAltitude = prefs.getFloat(AltitudeWidgetProvider.KEY_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE)
        val now = System.currentTimeMillis()

        // 1분 윈도우에 현재 고도 추가
        altitudeHistory.addLast(Pair(now, latestSmoothedAltitude))

        // 1분 이전 데이터 제거
        while (altitudeHistory.isNotEmpty() &&
            now - altitudeHistory.first().first > WINDOW_DURATION_MS) {
            altitudeHistory.removeFirst()
        }

        // 1분 누적 변화량
        val accumulatedChange = if (altitudeHistory.size >= 2) {
            latestSmoothedAltitude - altitudeHistory.first().second
        } else 0f

        prefs.edit()
            .putFloat(AltitudeWidgetProvider.KEY_PREV_ALTITUDE, prevAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE, latestSmoothedAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, accumulatedChange)
            .putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, true)
            .apply()

        AltitudeWidgetProvider.updateWidgets(this)
    }

    // ============ 알림 ============

    private fun createNotificationChannel() {
        val channelName = getString(R.string.channel_name)
        val channelDesc = getString(R.string.channel_description)
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = channelDesc
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
