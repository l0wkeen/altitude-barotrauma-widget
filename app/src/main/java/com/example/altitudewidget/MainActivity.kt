package com.example.altitudewidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 위젯 알림을 위한 POST_NOTIFICATIONS 권한 요청 진입점.
 * 위젯만으로는 런타임 권한을 요청할 Activity가 없어 Android 13+에서
 * 알림이 전혀 표시되지 않던 문제를 해결하기 위해 추가됨.
 */
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updateStatusText(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText(hasNotificationPermission())
    }

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

    private fun updateStatusText(granted: Boolean) {
        findViewById<TextView>(R.id.text_permission_status).text =
            getString(if (granted) R.string.permission_granted else R.string.permission_denied)
    }
}
