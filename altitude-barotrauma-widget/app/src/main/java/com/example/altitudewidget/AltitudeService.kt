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
import androidx.core.app.NotificationCompat

/**
 * 기압 센서로 고도를 측정하고 SharedPreferences에 저장하는 Foreground Service
 *
 * 개선 사항:
 * - 5분 누적 변화량 기반 ear barotrauma 위험도 판단
 * - 기압 센서 없는 기기 명시적 처리
 * - 측정값 이동평균 적용 (노이즈 제거)
 */
class AltitudeService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null

    // 노이즈 제거용 이동평균 버퍼 (최근 5개 측정값)
    private val pressureBuffer = ArrayDeque<Float>(5)

    // 마지막으로 위젯에 저장한 시각 (30초 간격 제한)
    private var lastSaveTime: Long = 0L

    companion object {
        const val CHANNEL_ID      = "altitude_service_channel"
        const val NOTIFICATION_ID = 1001
        const val UPDATE_INTERVAL = 30_000L   // 저장 간격 30초
        const val BUFFER_SIZE     = 5         // 이동평균 샘플 수
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pressureSensor == null) {
            // 기압 센서 없음: 위젯에 에러 메시지 기록 후 서비스 종료
            markNoSensor()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))
        sensorManager.registerListener(this, pressureSensor!!, SensorManager.SENSOR_DELAY_NORMAL)
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_PRESSURE) return

        val now = System.currentTimeMillis()
        if (now - lastSaveTime < UPDATE_INTERVAL) return
        lastSaveTime = now

        // 이동평균으로 노이즈 제거
        if (pressureBuffer.size >= BUFFER_SIZE) pressureBuffer.removeFirst()
        pressureBuffer.addLast(event.values[0])
        val smoothedPressure = pressureBuffer.average().toFloat()

        val currentAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            smoothedPressure
        )
        saveAndUpdateWidget(currentAltitude, now)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun saveAndUpdateWidget(currentAltitude: Float, now: Long) {
        val prefs = getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)

        // 5분 전 기준 고도 기록 관리
        // history_altitude_N, history_time_N 으로 슬라이딩 윈도우 유지
        val historySize = 10  // 30초 × 10 = 5분
        val altitudes = (0 until historySize).map {
            prefs.getFloat("${AltitudeWidgetProvider.KEY_HISTORY_ALT}$it", Float.MIN_VALUE)
        }.toMutableList()
        val times = (0 until historySize).map {
            prefs.getLong("${AltitudeWidgetProvider.KEY_HISTORY_TIME}$it", 0L)
        }.toMutableList()

        // 슬라이딩 윈도우: 오래된 항목 제거 후 최신 값 추가
        altitudes.removeAt(0); altitudes.add(currentAltitude)
        times.removeAt(0); times.add(now)

        // 5분 내 유효한 측정값 중 가장 오래된 것과 비교 → 누적 변화량
        val fiveMinAgo = now - 5 * 60 * 1000L
        val baseIndex = times.indexOfFirst { it > 0L && it <= fiveMinAgo }
        val baseAltitude: Float? = if (baseIndex >= 0) altitudes[baseIndex] else null
        val accumulatedChange: Float? = baseAltitude?.let { currentAltitude - it }

        // 이전 저장값 (직전 30초 전)
        val prevAltitude: Float? =
            if (prefs.contains(AltitudeWidgetProvider.KEY_CURRENT_ALTITUDE))
                prefs.getFloat(AltitudeWidgetProvider.KEY_CURRENT_ALTITUDE, 0f)
            else null

        prefs.edit().apply {
            prevAltitude?.let { putFloat(AltitudeWidgetProvider.KEY_PREV_ALTITUDE, it) }
            putFloat(AltitudeWidgetProvider.KEY_CURRENT_ALTITUDE, currentAltitude)
            accumulatedChange?.let { putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, it) }
            putBoolean(AltitudeWidgetProvider.KEY_SENSOR_AVAILABLE, true)
            // 히스토리 저장
            for (i in 0 until historySize) {
                putFloat("${AltitudeWidgetProvider.KEY_HISTORY_ALT}$i", altitudes[i])
                putLong("${AltitudeWidgetProvider.KEY_HISTORY_TIME}$i", times[i])
            }
            apply()
        }

        val msg = AltitudeWidgetProvider.getActionRecommendation(accumulatedChange, this)
        updateForegroundNotification(
            "${"%.0f".format(currentAltitude)}m  " +
            (accumulatedChange?.let { "(5분 ${"%.1f".format(it)}m)  " } ?: "") + msg
        )
        AltitudeWidgetProvider.updateAllWidgets(this)
    }

    private fun markNoSensor() {
        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(AltitudeWidgetProvider.KEY_SENSOR_AVAILABLE, false).apply()
        AltitudeWidgetProvider.updateAllWidgets(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateForegroundNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
