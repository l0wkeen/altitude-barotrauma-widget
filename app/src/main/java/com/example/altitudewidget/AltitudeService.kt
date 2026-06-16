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
 * - TYPE_PRESSURE 센서로 기압(hPa) 측정
 * - SensorManager.getAltitude()로 해발고도 계산
 * - 이동평균 5개 샘플로 노이즈 제거
 * - 3초 간격으로 위젯 갱신
 * - 1분 슬라이딩 윈도우로 누적 변화량 계산
 * - 누적 변화량 기준 3단계 귀 압력 알림 (15m / 30m / 50m)
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

        // 알림 단계 (중복 방지용)
        private const val ALERT_NONE = 0
        private const val ALERT_LEVEL_1 = 1   // 15m 이상
        private const val ALERT_LEVEL_2 = 2   // 30m 이상
        private const val ALERT_LEVEL_3 = 3   // 50m 이상
    }

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // 이동평균 버퍼
    private val altitudeBuffer = ArrayDeque<Float>(MOVING_AVG_SIZE)

    // 1분 슬라이딩 윈도우
    private val altitudeHistory = ArrayDeque<Pair<Long, Float>>()

    private var latestSmoothedAltitude = AltitudeWidgetProvider.INVALID_ALTITUDE

    // 마지막으로 발송한 알림 단계 (같은 단계 중복 발송 방지)
    private var lastAlertLevel = ALERT_NONE

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

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

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
        val rawAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa
        )

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
        val prevAltitude = prefs.getFloat(
            AltitudeWidgetProvider.KEY_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE
        )
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
        sendAlertIfNeeded(accumulatedChange)
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

        // 변화가 완화되면 알림 단계 초기화
        if (currentLevel == ALERT_NONE) {
            lastAlertLevel = ALERT_NONE
            return
        }

        // 같은 단계 중복 알림 방지 (더 심해진 경우만 발송)
        if (currentLevel <= lastAlertLevel) return
        lastAlertLevel = currentLevel

        val (title, body) = when (currentLevel) {
            ALERT_LEVEL_3 -> Pair(
                "🚨 귀 먹먹함 심각",
                "발살바법을 시도하세요 (코 막고 코 풀기)"
            )
            ALERT_LEVEL_2 -> Pair(
                "⚠️ 귀 먹먹함 주의",
                "하품하거나 침을 삼켜보세요"
            )
            else -> Pair(
                "💧 귀 압력 변화 감지",
                "물을 마시거나 하품해 보세요"
            )
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    // ============ 알림 채널 ============

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Foreground Service 채널 (상시)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
        )

        // 귀 압력 알림 채널 (이벤트성)
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "귀 압력 변화 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
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
