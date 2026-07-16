package com.example.altitudewidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 앱 진입점 — 알림 권한 요청과 사용자 증상 직접 기록을 담당한다.
 *
 * 위젯만으로는 런타임 권한을 요청할 Activity가 없어 Android 13+에서
 * 알림이 전혀 표시되지 않던 문제를 해결하기 위해 추가되었다.
 *
 * 증상 기록 버튼은 AltitudeService가 변화량만으로 추정하던 symptomLevel을
 * 대체하는 실제 사용자 입력으로, EarLogRepository.getPersonalThreshold()가
 * 자기 참조 없이 진짜 개인화 데이터를 사용하도록 한다.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val HISTORY_SIZE = 8
        private const val LOCATION_MAX_AGE_MS = 120_000L   // 이보다 오래된 위치는 새로 요청
        private const val LOCATION_TIMEOUT_MS = 15_000L    // 위치 단일 요청 최대 대기
    }

    private val logExecutor = Executors.newSingleThreadExecutor()
    private val timeFormatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updatePermissionStatusText(granted)
        }

    private val requestLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                startAutoCalibration()
            } else {
                Toast.makeText(this, R.string.calibration_location_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.btn_symptom_mild).setOnClickListener {
            logSymptom(EarLogData.SYMPTOM_MILD)
        }
        findViewById<Button>(R.id.btn_symptom_severe).setOnClickListener {
            logSymptom(EarLogData.SYMPTOM_SEVERE)
        }
        findViewById<Button>(R.id.btn_clear_logs).setOnClickListener {
            confirmClearLogs()
        }
        findViewById<Button>(R.id.btn_battery_optimization).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
        findViewById<Button>(R.id.btn_calibrate_auto).setOnClickListener {
            startAutoCalibration()
        }
        findViewById<Button>(R.id.btn_calibrate_manual).setOnClickListener {
            showManualCalibration()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusText(hasNotificationPermission())
        updateBatteryStatusText()
        updateCalibrationStatusText()
        updateCurrentStatusText()
        loadLogs()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        logExecutor.shutdown()
    }

    // ============ 알림 권한 ============
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updatePermissionStatusText(granted: Boolean) {
        findViewById<TextView>(R.id.text_permission_status).text =
            getString(if (granted) R.string.permission_granted else R.string.permission_denied)
    }

    // ============ 배터리 최적화 ============
    // 위젯/서비스가 화면이 꺼진 뒤에도 계속 측정하려면 배터리 최적화 대상에서
    // 빠져야 한다. 특히 Non-wakeup 기압 센서를 쓰는 기종에서는 이 설정이 안 되어
    // 있으면 화면이 꺼졌을 때 센서 콜백 자체가 끊기는 경우가 실제로 있다.
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryStatusText() {
        val ignoring = isIgnoringBatteryOptimizations()
        findViewById<TextView>(R.id.text_battery_status).text = getString(
            if (ignoring) R.string.battery_optimization_ignored
            else R.string.battery_optimization_not_ignored
        )
        // 이미 배터리 최적화에서 제외된 상태면 버튼을 숨긴다(상태 문구만 표시).
        findViewById<Button>(R.id.btn_battery_optimization).visibility =
            if (ignoring) View.GONE else View.VISIBLE
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) return
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        )
    }

    // ============ 고도 보정 ============
    // 기압 고도는 표준 해수면 기압(1013.25 hPa)을 가정하므로 그날의 실제 기압에 따라
    // 절대 고도가 수십 m 어긋날 수 있다. 실제 고도를 기준으로 오프셋을 저장해 표시를 보정한다.
    // 변화량 기반 알림 로직에는 영향이 없다(오프셋이 차이값에서 상쇄됨).

    private fun updateCalibrationStatusText() {
        val offset = widgetPrefs().getFloat(AltitudeWidgetProvider.KEY_ALTITUDE_OFFSET, 0f)
        findViewById<TextView>(R.id.text_calibration_status).text =
            if (offset == 0f) getString(R.string.calibration_none)
            else getString(R.string.calibration_applied_format, offset)
    }

    private fun currentRawAltitude(): Float =
        widgetPrefs().getFloat(AltitudeWidgetProvider.KEY_ALTITUDE_RAW, AltitudeWidgetProvider.INVALID_ALTITUDE)

    private fun saveOffset(offset: Float) {
        widgetPrefs().edit()
            .putFloat(AltitudeWidgetProvider.KEY_ALTITUDE_OFFSET, offset)
            .apply()
        updateCalibrationStatusText()
        // 위젯/현재 상태 표시는 다음 서비스 갱신(최대 3초) 때 반영된다.
        updateCurrentStatusText()
    }

    // ---- 수동 보정 (C) ----
    private fun showManualCalibration() {
        if (currentRawAltitude() == AltitudeWidgetProvider.INVALID_ALTITUDE) {
            Toast.makeText(this, R.string.calibration_no_raw, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.calibration_manual_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.calibration_manual_title)
            .setMessage(R.string.calibration_manual_message)
            .setView(input)
            .setPositiveButton(R.string.calibration_ok) { _, _ ->
                val trueAlt = input.text.toString().trim().toFloatOrNull()
                val raw = currentRawAltitude()
                if (trueAlt == null || raw == AltitudeWidgetProvider.INVALID_ALTITUDE) {
                    Toast.makeText(this, R.string.calibration_manual_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    saveOffset(trueAlt - raw)
                    Toast.makeText(this, R.string.calibration_manual_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.calibration_reset) { _, _ ->
                saveOffset(0f)
                Toast.makeText(this, R.string.calibration_reset_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.calibration_cancel, null)
            .show()
    }

    // ---- 자동 보정 (B): GPS 좌표 → 고도 API ----
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAutoCalibration() {
        if (!hasLocationPermission()) {
            requestLocationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        if (currentRawAltitude() == AltitudeWidgetProvider.INVALID_ALTITUDE) {
            Toast.makeText(this, R.string.calibration_no_raw, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.calibration_locating, Toast.LENGTH_SHORT).show()
        requestCurrentLocation { location ->
            if (location == null) {
                Toast.makeText(this, R.string.calibration_location_failed, Toast.LENGTH_LONG).show()
                return@requestCurrentLocation
            }
            val lat = location.latitude
            val lon = location.longitude
            logExecutor.execute {
                val elevation = ElevationApi.fetchElevation(lat, lon)
                runOnUiThread {
                    if (elevation == null) {
                        Toast.makeText(this, R.string.calibration_api_failed, Toast.LENGTH_LONG).show()
                    } else {
                        val raw = currentRawAltitude()
                        if (raw != AltitudeWidgetProvider.INVALID_ALTITUDE) {
                            saveOffset(elevation.toFloat() - raw)
                            Toast.makeText(
                                this,
                                getString(R.string.calibration_auto_done_format, elevation),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")  // 호출 전 hasLocationPermission()으로 확인함
    private fun requestCurrentLocation(callback: (Location?) -> Unit) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        val lastKnown = providers
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (lastKnown != null &&
            System.currentTimeMillis() - lastKnown.time < LOCATION_MAX_AGE_MS
        ) {
            callback(lastKnown)
            return
        }

        val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            callback(lastKnown) // 최후: 오래됐더라도 마지막 위치라도 사용
            return
        }

        var finished = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished) return
                finished = true
                runCatching { lm.removeUpdates(this) }
                callback(location)
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        mainHandler.postDelayed({
            if (!finished) {
                finished = true
                runCatching { lm.removeUpdates(listener) }
                callback(lastKnown)
            }
        }, LOCATION_TIMEOUT_MS)
    }

    // ============ 현재 고도 상태 ============
    private fun widgetPrefs() =
        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)

    private fun updateCurrentStatusText() {
        val prefs = widgetPrefs()
        val altitude = prefs.getFloat(AltitudeWidgetProvider.KEY_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE)
        val statusView = findViewById<TextView>(R.id.text_current_status)
        statusView.text = when {
            prefs.getBoolean(AltitudeWidgetProvider.KEY_SENSOR_STALLED, false) ->
                getString(R.string.current_status_stalled)
            altitude == AltitudeWidgetProvider.INVALID_ALTITUDE ->
                getString(R.string.current_status_no_data)
            else -> {
                val accumulatedChange = prefs.getFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, 0f)
                getString(R.string.current_status_format, altitude, accumulatedChange)
            }
        }
    }

    // ============ 증상 기록 ============
    private fun logSymptom(symptomLevel: Int) {
        val prefs = widgetPrefs()
        val altitude = prefs.getFloat(AltitudeWidgetProvider.KEY_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE)
        if (altitude == AltitudeWidgetProvider.INVALID_ALTITUDE) {
            Toast.makeText(this, R.string.current_status_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val logEntry = EarLogData(
            altitude = altitude,
            accumulatedChange = prefs.getFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, 0f),
            immediateChange = prefs.getFloat(AltitudeWidgetProvider.KEY_IMMEDIATE_CHANGE, 0f),
            symptomLevel = symptomLevel,
            triggeredByAlert = false
        )
        val appContext = applicationContext
        logExecutor.execute {
            EarLogRepository.save(appContext, logEntry)
            runOnUiThread {
                Toast.makeText(this, R.string.symptom_saved_toast, Toast.LENGTH_SHORT).show()
                loadLogs()
            }
        }
    }

    // ============ 기록 통계 & 최근 기록 ============
    private fun loadLogs() {
        val appContext = applicationContext
        logExecutor.execute {
            val logs = EarLogRepository.loadAll(appContext)
            val threshold = EarLogRepository.getPersonalThreshold(appContext)
            val recent = logs.takeLast(HISTORY_SIZE).reversed()
            runOnUiThread {
                updateLogStatsText(logs.size, threshold)
                updateHistoryText(recent)
            }
        }
    }

    private fun updateLogStatsText(count: Int, threshold: Float) {
        // count == 0일 때도 getPersonalThreshold()는 기본값(AlertThresholds.LEVEL1_DEFAULT_MPM)을
        // 반환하므로 별도 분기 없이 그대로 표시해도 문구가 어색하지 않다.
        findViewById<TextView>(R.id.text_log_stats).text =
            getString(R.string.log_stats_format, count, threshold)
    }

    private fun updateHistoryText(recent: List<EarLogData>) {
        val historyView = findViewById<TextView>(R.id.text_history)
        if (recent.isEmpty()) {
            historyView.text = getString(R.string.history_empty)
            return
        }
        historyView.text = recent.joinToString("\n") { log ->
            val symptomSuffix = when (log.symptomLevel) {
                EarLogData.SYMPTOM_MILD -> getString(R.string.history_symptom_mild)
                EarLogData.SYMPTOM_SEVERE -> getString(R.string.history_symptom_severe)
                else -> ""
            }
            getString(
                R.string.history_entry_format,
                timeFormatter.format(Date(log.timestamp)),
                log.altitude,
                log.accumulatedChange,
                symptomSuffix
            )
        }
    }

    // ============ 기록 초기화 ============
    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_logs_confirm_title)
            .setMessage(R.string.clear_logs_confirm_message)
            .setPositiveButton(R.string.clear_logs_confirm_ok) { _, _ -> clearLogs() }
            .setNegativeButton(R.string.clear_logs_confirm_cancel, null)
            .show()
    }

    private fun clearLogs() {
        val appContext = applicationContext
        logExecutor.execute {
            EarLogRepository.clearAll(appContext)
            runOnUiThread {
                Toast.makeText(this, R.string.clear_logs_done_toast, Toast.LENGTH_SHORT).show()
                loadLogs()
            }
        }
    }
}
