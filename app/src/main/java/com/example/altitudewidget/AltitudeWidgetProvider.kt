package com.example.altitudewidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * Ear Barotrauma 예방 고도 변화 측정 위젯
 * 
 * 동작 흐름:
 * 1. 고도 측정 (GPS 또는 기압 센서)
 * 2. 고도 변화량 계산
 * 3. 위험 구간 판단
 * 4. 사용자 행동 추천 알림 전송
 */
class AltitudeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateWidgetView(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateWidgetView(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_altitude)

        // TODO: 실제 GPS 또는 기압 센서로부터 고도 값 받아오기
        val currentAltitude: Double? = null
        val altitudeChange: Double? = null

        val altitudeText = if (currentAltitude != null)
            "현재 고도: ${String.format("%.1f", currentAltitude)} m"
        else
            "현재 고도: 측정 중..."

        val changeText = if (altitudeChange != null)
            "변화량: ${String.format("%+.1f", altitudeChange)} m"
        else
            "변화량: -"

        val actionMessage = getActionRecommendation(altitudeChange)

        views.setTextViewText(R.id.text_altitude, altitudeText)
        views.setTextViewText(R.id.text_altitude_change, changeText)
        views.setTextViewText(R.id.text_action, actionMessage)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * 고도 변화량에 따라 ear barotrauma 예방 행동을 추천
     * @param change 고도 변화량 (양수=상승, 음수=하강) (m)
     * @return 추천 행동 메시지
     */
    private fun getActionRecommendation(change: Double?): String {
        if (change == null) return "행동 추천 대기중"
        return when {
            change >= 50.0  -> "⚠️ 발살바 (코 막고 후풍)"
            change >= 30.0  -> "하품 또는 쳘 삼키기"
            change >= 15.0  -> "물을 조금 마시세요"
            change <= -50.0 -> "⚠️ 발살바 (코 막고 후풍)"
            change <= -30.0 -> "하품 또는 쳘 삼키기"
            change <= -15.0 -> "물을 조금 마시세요"
            else            -> "고도 변화 정상"
        }
    }
}
