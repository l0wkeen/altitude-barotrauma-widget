package com.example.altitudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// AltitudeMeasureReceiver는 더 이상 사용되지 않습니다.
// ACTION_MEASURE 브로드캐스트를 발송하는 호출자가 없으며,
// 서비스 시작은 AltitudeWidgetProvider에서 직접 처리합니다.
// AndroidManifest.xml 등록이 남아 있으므로 컴파일 오류 방지용 stub으로 유지합니다.

class AltitudeMeasureReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_MEASURE = "com.example.altitudewidget.ACTION_MEASURE"
    }
    override fun onReceive(context: Context, intent: Intent) = Unit
}
