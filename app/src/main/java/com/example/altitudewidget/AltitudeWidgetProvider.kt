package com.example.altitudewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews

/**
 * Ear Barotrauma 예방 고도 변화 측정 위젯
 *
 * 동작 흐름:
 * 1. AltitudeService가 기압 센서로 고도 측정 후 SharedPreferences에 저장
 * 2. 이 클래스는 SharedPreferences를 읽어 위젯 UI 갱신
 * 3. 고도 변화량 기준 행동 추천 표시
 */
class AltitudeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PREFS_NAME = "AltitudePrefs"
        const val KEY_PREV_ALTITUDE = "prev_altitude"
        const val KEY_CURRENT_ALTITUDE = "current_altitude"

        /** 위젯 전체 갱신 (AltitudeService에서 호출) */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AltitudeWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidgetView(context, manager, id)
            }
        }

        fun updateWidgetView(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val currentAltitude: Float? =
                if (prefs.contains(KEY_CURRENT_ALTITUDE))
                    prefs.getFloat(KEY_CURRENT_ALTITUDE, 0f)
                else null

            val prevAltitude: Float? =
                if (prefs.contains(KEY_PREV_ALTITUDE))
                    prefs.getFloat(KEY_PREV_ALTITUDE, 0f)
                else null

            val altitudeChange: Float? =
                if (currentAltitude != null && prevAltitude != null)
                    currentAltitude - prevAltitude
                else null

            val altitudeText = if (currentAltitude != null)
                "현재 고도: ${"%,.0f".format(currentAltitude)} m"
            else
                "현재 고도: 측정 중..."

            val changeText = if (altitudeChange != null)
                "변화량: ${String.format("%+.1f", altitudeChange)} m"
            else
                "변화량: -"

            val actionMessage = getActionRecommendation(altitudeChange)

            val views = RemoteViews(context.packageName, R.layout.widget_altitude)
            views.setTextViewText(R.id.text_altitude, altitudeText)
            views.setTextViewText(R.id.text_altitude_change, changeText)
            views.setTextViewText(R.id.text_action, actionMessage)

            // 위젯 탭 시 AltitudeService 재시작
            val intent = Intent(context, AltitudeService::class.java)
            val pendingIntent = PendingIntent.getService(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * 고도 변화량에 따라 ear barotrauma 예방 행동 추천
         * @param change 고도 변화량 (m), 양수=상승, 음수=하강
         */
        fun getActionRecommendation(change: Float?): String {
            if (change == null) return "센서 초기화 중..."
            return when {
                change >= 50f  -> "⚠️ 발살바 (코 막고 후풍)"
                change >= 30f  -> "하품 또는 침 삼키기"
                change >= 15f  -> "물을 조금 마시세요"
                change <= -50f -> "⚠️ 발살바 (코 막고 후풍)"
                change <= -30f -> "하품 또는 침 삼키기"
                change <= -15f -> "물을 조금 마시세요"
                else           -> "고도 변화 정상"
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // 가 위젯 추가될 때 AltitudeService 시작
        context.startService(Intent(context, AltitudeService::class.java))
        for (appWidgetId in appWidgetIds) {
            updateWidgetView(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.startService(Intent(context, AltitudeService::class.java))
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.stopService(Intent(context, AltitudeService::class.java))
    }
}
