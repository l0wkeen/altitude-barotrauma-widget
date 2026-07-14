package com.example.altitudewidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    private val logExecutor = Executors.newSingleThreadExecutor()

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
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusText(hasNotificationPermission())
        updateCurrentStatusText()
        loadLogStats()
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

    // ============ 현재 고도 상태 ============
    private fun widgetPrefs() =
        getSharedPreferences(AltitudeWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)

    private fun updateCurrentStatusText() {
        val prefs = widgetPrefs()
        val altitude = prefs.getFloat(AltitudeWidgetProvider.KEY_ALTITUDE, AltitudeWidgetProvider.INVALID_ALTITUDE)
        val statusView = findViewById<TextView>(R.id.text_current_status)
        statusView.text = if (altitude == AltitudeWidgetProvider.INVALID_ALTITUDE) {
            getString(R.string.current_status_no_data)
        } else {
            val accumulatedChange = prefs.getFloat(AltitudeWidgetProvider.KEY_ACCUMULATED_CHANGE, 0f)
            getString(R.string.current_status_format, altitude, accumulatedChange)
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
                loadLogStats()
            }
        }
    }

    private fun loadLogStats() {
        val appContext = applicationContext
        logExecutor.execute {
            val count = EarLogRepository.getCount(appContext)
            val threshold = EarLogRepository.getPersonalThreshold(appContext)
            runOnUiThread {
                val statsView = findViewById<TextView>(R.id.text_log_stats)
                statsView.text = if (count == 0) {
                    getString(R.string.log_stats_none)
                } else {
                    getString(R.string.log_stats_format, count, threshold)
                }
            }
        }
    }
}
