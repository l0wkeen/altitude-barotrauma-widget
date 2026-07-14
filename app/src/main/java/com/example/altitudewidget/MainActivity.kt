package com.example.altitudewidget

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
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
    }

    private val logExecutor = Executors.newSingleThreadExecutor()
    private val timeFormatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updatePermissionStatusText(granted)
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
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusText(hasNotificationPermission())
        updateBatteryStatusText()
        updateCurrentStatusText()
        loadLogs()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        findViewById<TextView>(R.id.text_battery_status).text = getString(
            if (isIgnoringBatteryOptimizations()) R.string.battery_optimization_ignored
            else R.string.battery_optimization_not_ignored
        )
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
        findViewById<TextView>(R.id.text_log_stats).text = if (count == 0) {
            getString(R.string.log_stats_none)
        } else {
            getString(R.string.log_stats_format, count, threshold)
        }
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
