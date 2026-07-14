package com.example.altitudewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import kotlin.math.abs

/** 고도 히스토리 포인트 — Pair<Long,Float> 오토박싱 제거 */
private data class AltitudePoint(val timeMs: Long, val altitudeM: Float)

class AltitudeService : Service(), SensorEventListener {
    companion object {
        private const val TAG = "AltitudeService"
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
        // 로그 저장 주기: 매 N번째 업데이트마다 1회 저장 (3초 * 10 = 30초마다)
        private const val LOG_SAVE_INTERVAL = 10
    }

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // Float 원시값 배열 — 박싱 없음
    private val altitudeBuffer = ArrayDeque<Float>(MOVING_AVG_SIZE)
    private val altitudeHistory = ArrayDeque<AltitudePoint>()
    private var latestSmoothedAltitude = AltitudeWidgetProvider.INVALID_ALTITUDE
    private var lastAlertLevel = ALERT_NONE
    private var firstSensorValueReceived = false

    // 직전 고도값 추적 (immediateChange 계산용)
    private var previousAltitude = AltitudeWidgetProvider.INVALID_ALTITUDE

    // 로그 저장 카운터
    private var updateCount = 0

    // 로그 파일 I/O(디스크 접근)를 메인 스레드에서 분리하기 위한 전용 스레드
    private val logExecutor = Executors.newSingleThreadExecutor()

    // 개인 맞춤 1단계 임계값 — 백그라운드에서 갱신되므로 volatile
    @Volatile private var personalLevel1Threshold = EarLogRepository.DEFAULT_THRESHOLD_LEVEL1

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

        firstSensorValueReceived = false
        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, 0f)
            .putBoolean(AltitudeWidgetProvider.KEY_IS_WARMING_UP, true)
            .apply()

        createNotificationChannels()

        // Android 14(API 34)+ 에서는 foregroundServiceType 명시 필수
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        // SENSOR_DELAY_NORMAL (~200ms): 위젯은 3초 주기 갱신이라 배터리 최적화
        val registered = sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "Sensor registration status: $registered")

        handler.post(updateRunnable)
        logExecutor.execute {
            personalLevel1Threshold = EarLogRepository.getPersonalThreshold(applicationContext)
        }
        Log.d(TAG, "Service Created and updateRunnable started")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        if (pressureSensor != null) sensorManager.unregisterListener(this)
        logExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ============ 센서 콜백 ============
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val pressure = event.values[0]
        val rawAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure
        )

        Log.d(TAG, "Sensor raw value: $pressure, Altitude: $rawAltitude")
        if (altitudeBuffer.size >= MOVING_AVG_SIZE) altitudeBuffer.removeFirst()
        altitudeBuffer.addLast(rawAltitude)
        latestSmoothedAltitude = altitudeBuffer.average().toFloat()

        if (!firstSensorValueReceived) {
            firstSensorValueReceived = true
            previousAltitude = latestSmoothedAltitude
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
        altitudeHistory.addLast(AltitudePoint(now, latestSmoothedAltitude))

        // 1분 초과 데이터 제거
        while (altitudeHistory.isNotEmpty() &&
            now - altitudeHistory.first().timeMs > WINDOW_DURATION_MS) {
            altitudeHistory.removeFirst()
        }

        val isWarmingUp = !firstSensorValueReceived
        val accumulatedChange = if (!isWarmingUp) {
            latestSmoothedAltitude - altitudeHistory.first().altitudeM
        } else {
            0f
        }

        // 직전 업데이트 대비 즉각 변화량 계산
        val immediateChange = if (previousAltitude != AltitudeWidgetProvider.INVALID_ALTITUDE) {
            latestSmoothedAltitude - previousAltitude
        } else {
            0f
        }

        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE, latestSmoothedAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, accumulatedChange)
            .putFloat(AltitudeWidgetProvider.KEY_IMMEDIATE_CHANGE, immediateChange)
            .putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, true)
            .putBoolean(AltitudeWidgetProvider.KEY_IS_WARMING_UP, isWarmingUp)
            .apply()

        AltitudeWidgetProvider.updateWidgets(this)

        if (!isWarmingUp) {
            val alertFired = sendAlertIfNeeded(accumulatedChange, immediateChange)

            // 30초(=LOG_SAVE_INTERVAL번)마다 주기적 로그 저장
            // 알림이 발생한 경우에도 즉시 저장 (triggeredByAlert=true)
            updateCount++
            if (alertFired || updateCount >= LOG_SAVE_INTERVAL) {
                // symptomLevel은 여기서 추정하지 않는다 — 실제 증상 여부는 사용자가
                // MainActivity에서 직접 보고한 기록만을 근거로 삼아야 개인화 임계값이
                // 순환 논리(변화량 → 추정 증상 → 다시 변화량 기준 임계값) 없이 계산된다.
                val logEntry = EarLogData(
                    timestamp = now,
                    altitude = latestSmoothedAltitude,
                    accumulatedChange = accumulatedChange,
                    immediateChange = immediateChange,
                    symptomLevel = EarLogData.SYMPTOM_NONE,
                    triggeredByAlert = alertFired
                )
                val appContext = applicationContext
                logExecutor.execute {
                    EarLogRepository.save(appContext, logEntry)
                    personalLevel1Threshold = EarLogRepository.getPersonalThreshold(appContext)
                }
                if (updateCount >= LOG_SAVE_INTERVAL) updateCount = 0
                Log.d(TAG, "EarLog saved: alt=${latestSmoothedAltitude}m, change=${accumulatedChange}m, alert=$alertFired")
            }
        }

        previousAltitude = latestSmoothedAltitude
    }

    // ============ 귀 압력 알림 (반환값: 알림이 실제로 발생했는지) ============
    private fun sendAlertIfNeeded(accumulatedChange: Float, immediateChange: Float): Boolean {
        val absChange = abs(accumulatedChange)
        val currentLevel = when {
            absChange >= 50f -> ALERT_LEVEL_3
            absChange >= 30f -> ALERT_LEVEL_2
            absChange >= personalLevel1Threshold -> ALERT_LEVEL_1
            else -> ALERT_NONE
        }
        if (currentLevel == ALERT_NONE) { lastAlertLevel = ALERT_NONE; return false }
        if (currentLevel <= lastAlertLevel) return false
        lastAlertLevel = currentLevel

        val (title, body) = when (currentLevel) {
            ALERT_LEVEL_3 -> getString(R.string.alert_title_level3) to getString(R.string.alert_body_level3)
            ALERT_LEVEL_2 -> getString(R.string.alert_title_level2) to getString(R.string.alert_body_level2)
            else -> getString(R.string.alert_title_level1) to getString(R.string.alert_body_level1)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .addAction(
                    android.R.drawable.ic_menu_add,
                    getString(R.string.alert_action_mild),
                    symptomActionPendingIntent(EarLogData.SYMPTOM_MILD, accumulatedChange, immediateChange, requestCode = 101)
                )
                .addAction(
                    android.R.drawable.ic_menu_add,
                    getString(R.string.alert_action_severe),
                    symptomActionPendingIntent(EarLogData.SYMPTOM_SEVERE, accumulatedChange, immediateChange, requestCode = 102)
                )
                .build()
        )
        return true
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun symptomActionPendingIntent(
        symptomLevel: Int,
        accumulatedChange: Float,
        immediateChange: Float,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(this, SymptomLogReceiver::class.java).apply {
            action = SymptomLogReceiver.ACTION_LOG_SYMPTOM
            putExtra(SymptomLogReceiver.EXTRA_SYMPTOM_LEVEL, symptomLevel)
            putExtra(SymptomLogReceiver.EXTRA_ALTITUDE, latestSmoothedAltitude)
            putExtra(SymptomLogReceiver.EXTRA_ACCUMULATED_CHANGE, accumulatedChange)
            putExtra(SymptomLogReceiver.EXTRA_IMMEDIATE_CHANGE, immediateChange)
            putExtra(SymptomLogReceiver.EXTRA_NOTIFICATION_ID, ALERT_NOTIFICATION_ID)
        }
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.alert_channel_description)
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
