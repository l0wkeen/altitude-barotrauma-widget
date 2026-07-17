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
        // 경고 알림 채널 ID — MainActivity의 테스트 알림에서도 재사용하므로 공개한다.
        const val ALERT_CHANNEL_ID = "altitude_alert"
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
        // 이 시간 동안 센서 콜백이 없으면 "응답 없음"으로 간주 — 배터리 최적화나
        // Non-wakeup 센서가 화면 꺼짐 중 콜백을 막는 기종에서 실제로 발생한다.
        private const val SENSOR_STALE_MS = 20_000L
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

    // 마지막으로 센서 콜백을 받은 시각 — SENSOR_STALE_MS 이상 갱신 안 되면 응답 없음으로 판단
    private var lastSensorEventTime = 0L

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
            .putBoolean(AltitudeWidgetProvider.KEY_SENSOR_STALLED, false)
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
        lastSensorEventTime = System.currentTimeMillis()
        Log.d(TAG, "Sensor registration status: $registered")

        handler.post(updateRunnable)
        logExecutor.execute { refreshPersonalThreshold() }
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
        lastSensorEventTime = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()

        // 센서 등록 후 한 번도 값을 못 받았거나, 받다가 중간에 멈춘 경우 —
        // 배터리 최적화나 Non-wakeup 센서 제한으로 실제 기기에서 발생할 수 있다.
        // 오래된(멈춘) 값으로 계속 "정상"이라고 표시하지 않도록 여기서 걸러낸다.
        if (now - lastSensorEventTime > SENSOR_STALE_MS) {
            getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AltitudeWidgetProvider.KEY_SENSOR_STALLED, true)
                .apply()
            AltitudeWidgetProvider.updateWidgets(this)
            return
        }

        if (latestSmoothedAltitude == AltitudeWidgetProvider.INVALID_ALTITUDE) return

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

        // 보정 오프셋을 적용한 표시용 고도. 변화량(accumulatedChange/immediateChange)은
        // 차이값이라 오프셋이 상쇄되므로 원시값 기준 그대로 사용한다.
        val prefs = getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        val offset = prefs.getFloat(AltitudeWidgetProvider.KEY_ALTITUDE_OFFSET, 0f)
        val calibratedAltitude = latestSmoothedAltitude + offset

        prefs.edit()
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE, calibratedAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE_RAW, latestSmoothedAltitude)
            .putFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, accumulatedChange)
            .putFloat(AltitudeWidgetProvider.KEY_IMMEDIATE_CHANGE, immediateChange)
            .putBoolean(AltitudeWidgetProvider.KEY_HAS_SENSOR, true)
            .putBoolean(AltitudeWidgetProvider.KEY_IS_WARMING_UP, isWarmingUp)
            .putBoolean(AltitudeWidgetProvider.KEY_SENSOR_STALLED, false)
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
                    altitude = calibratedAltitude,
                    accumulatedChange = accumulatedChange,
                    immediateChange = immediateChange,
                    symptomLevel = EarLogData.SYMPTOM_NONE,
                    triggeredByAlert = alertFired
                )
                val appContext = applicationContext
                logExecutor.execute {
                    EarLogRepository.save(appContext, logEntry)
                    refreshPersonalThreshold()
                }
                if (updateCount >= LOG_SAVE_INTERVAL) updateCount = 0
                Log.d(TAG, "EarLog saved: alt=${latestSmoothedAltitude}m, change=${accumulatedChange}m, alert=$alertFired")
            }
        }

        previousAltitude = latestSmoothedAltitude
    }

    // 개인 맞춤 임계값을 다시 계산하고 SharedPreferences에도 반영한다.
    // 위젯(AltitudeWidgetProvider)은 이 Service 인스턴스에 직접 접근할 수 없으므로,
    // 위젯의 행동 추천 표시가 실제 알림 기준과 어긋나지 않으려면 prefs를 통해 공유해야 한다.
    // logExecutor(백그라운드 스레드)에서만 호출한다.
    private fun refreshPersonalThreshold() {
        val threshold = EarLogRepository.getPersonalThreshold(applicationContext)
        personalLevel1Threshold = threshold
        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AltitudeWidgetProvider.KEY_PERSONAL_THRESHOLD, threshold)
            .apply()
    }

    // ============ 귀 압력 알림 (반환값: 알림이 실제로 발생했는지) ============
    private fun sendAlertIfNeeded(accumulatedChange: Float, immediateChange: Float): Boolean {
        val absChange = abs(accumulatedChange)
        val currentLevel = when {
            absChange >= AlertThresholds.LEVEL3_MPM -> ALERT_LEVEL_3
            absChange >= AlertThresholds.LEVEL2_MPM -> ALERT_LEVEL_2
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
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
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

        // 3단계(발살바 권장)는 알림을 펼치면 발살바 방법이 보이도록 확장형으로 표시한다.
        if (currentLevel == ALERT_LEVEL_3) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body + "\n\n" + getString(R.string.alert_valsalva_steps))
            )
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, builder.build())
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
        // 알림에서 기록되는 고도도 표시용(보정 적용) 값으로 통일한다.
        val offset = getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(AltitudeWidgetProvider.KEY_ALTITUDE_OFFSET, 0f)
        val intent = Intent(this, SymptomLogReceiver::class.java).apply {
            action = SymptomLogReceiver.ACTION_LOG_SYMPTOM
            putExtra(SymptomLogReceiver.EXTRA_SYMPTOM_LEVEL, symptomLevel)
            putExtra(SymptomLogReceiver.EXTRA_ALTITUDE, latestSmoothedAltitude + offset)
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
