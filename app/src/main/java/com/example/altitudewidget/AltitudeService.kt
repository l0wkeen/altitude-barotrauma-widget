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
import kotlin.math.abs

/**
 * 기압 센서로 고도를 측정하고 SharedPreferences에 저장하는 Foreground Service
 *
 * isWarmingUp 설계:
 * - firstSensorValueReceived == false → 센서값 아직 없음 → isWarmingUp = true
 * - firstSensorValueReceived == true  → 센서값 있음 → isWarmingUp = false, 바로 변화량 계산
 * - altitudeHistory에 addLast 후 size를 체크하는 구조 버그 제거
 */
class AltitudeService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "altitude_channel"
        private const val ALERT_CHANNEL_ID = "altitude_alert"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 3_000L
        private const val WINDOW_DURATION_MS = 60_000L
        private const val MOVING_AVG_SIZE = 5

        private const val ALERT_NONE = 0
        private const val ALERT_LEVEL_1 = 1
        private const val ALERT_LEVEL_2 = 2
        private const val ALERT_LEVEL_3 = 3
    }

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    private val altitudeBuffer = ArrayDeque<Float>(MOVING_AVG_SIZE)
    private val altitudeHistory = ArrayDeque<Pair<Long, Float>>()
    private var latestSmoothedAltitude = AltitudeWidgetProvider.INVALID_ALTITUDE
    private var lastAlertLevel = ALERT_NONE

    // 첫 센서값 수신 여부: false = 아직 없음(워밍업), true = 있음(정상)
    private var firstSensorValueReceived = false

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

        // 서비스 시작 시 리셋
        // KEY_IS_WARMING_UP은 여기서 true로 설정하지 않음
        // → firstSensorValueReceived 인메모리 플래그로 제어하므로
        //   onCreate 재호출 시에도 안전하게 작동
        firstSensorValueReceived = false
        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, 0f)
            .putBoolean(AltitudeWidgetProvider.KEY_IS_WARMING_UP, true)  // 센서값 도착 전에만 true
            .apply()

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI)
        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        if (pressureSensor != null) sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ============ 센서 콜백 ============

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return

        val rawAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0]
        )
        if (altitudeBuffer.size >= MOVING_AVG_SIZE) altitudeBuffer.removeFirst()
        altitudeBuffer.addLast(rawAltitude)
        latestSmoothedAltitude = altitudeBuffer.average().toFloat()

        // 첫 값 도착 즉시: isWarmingUp = false로 전환 + 위젯 즉시 갱신
        if (!firstSensorValueReceived) {
            firstSensorValueReceived = true
            // KEY_IS_WARMING_UP을 즉시 false로 저장
            getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AltitudeWidgetProvider.KEY_IS_WARMING_UP, false)
                .apply()
            saveAndUpdate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ============ 데이터 저장 & 위젯 갱신 ============

    private fun saveAndUpdate() {
        if (latestSmoothedAltitude == AltitudeWidgetProvider.INVALID_ALTITUDE) return

        val now = System.currentTimeMillis()

        altitudeHistory.addLast(Pair(now, latestSmoothedAltitude))

        // 1분 이전 데이터 제거
        while (altitudeHistory.isNotEmpty() &&
            now - altitudeHistory.first().first > WINDOW_DURATION_MS) {
            altitudeHistory.removeFirst()
        }

        // isWarmingUp은 firstSensorValueReceived로 제어
        // (altitudeHistory.size 체크 불필요: addLast 후에는 항상 1 이상)
        val isWarmingUp = !firstSensorValueReceived

        val accumulatedChange = if (!isWarmingUp) {
            latestSmoothedAltitude - altitudeHistory.first().second
        } else {
            0f
        }

        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE, latestSmoothedAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, accumulatedChange)
            .putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, true)
            .putBoolean(AltitudeWidgetProvider.KEY_IS_WARMING_UP, isWarmingUp)
            .apply()

        AltitudeWidgetProvider.updateWidgets(this)
        if (!isWarmingUp) sendAlertIfNeeded(accumulatedChange)
    }

    // ============ 귀 압력 알림 ============

    private fun sendAlertIfNeeded(accumulatedChange: Float) {
        val absChange = abs(accumulatedChange)
        val currentLevel = when {
            absChange >= 50f -> ALERT_LEVEL_3
            absChange >= 30f -> ALERT_LEVEL_2
            absChange >= 15f -> ALERT_LEVEL_1
            else             -> ALERT_NONE
        }

        if (currentLevel == ALERT_NONE) { lastAlertLevel = ALERT_NONE; return }
        if (currentLevel <= lastAlertLevel) return
        lastAlertLevel = currentLevel

        val (title, body) = when (currentLevel) {
            ALERT_LEVEL_3 -> Pair("🚨 귀 먹먹함 심각", "발살바법을 시도하세요 (코 막고 코 풀기)")
            ALERT_LEVEL_2 -> Pair("⚠️ 귀 먹먹함 주의", "하품하거나 침을 삼켜보세요")
            else           -> Pair("💧 귀 압력 변화 감지", "물을 마시거나 하품해 보세요")
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    // ============ 알림 채널 ============

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_description)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "귀 압력 변화 알림",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "고도 변화에 따른 귀 먹먹함 예방 알림"
                enableVibration(true)
            }
        )
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
