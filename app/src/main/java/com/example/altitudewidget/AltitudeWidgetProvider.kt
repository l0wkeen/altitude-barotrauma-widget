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
        const val KEY_ALTITUDE = "current_altitude"
        const val KEY_PREV_ALTITUDE = "prev_altitude"
        const val KEY_ACCUMULATED_CHANGE = "accumulated_change"
        const val KEY_HAS_SENSOR = "has_sensor"
        const val INVALID_ALTITUDE = Float.MIN_VALUE

        fun updateWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AltitudeWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val hasSensor = prefs.getBoolean(KEY_HAS_SENSOR, true)
            val currentAltitude = prefs.getFloat(KEY_ALTITUDE, INVALID_ALTITUDE)
            val prevAltitude = prefs.getFloat(KEY_PREV_ALTITUDE, INVALID_ALTITUDE)
            val accumulatedChange = prefs.getFloat(KEY_ACCUMULATED_CHANGE, 0f)

            val views = RemoteViews(context.packageName, R.layout.widget_altitude)

            // 위젯 클릭 시 앱 실행 인텐트
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            if (!hasSensor) {
                // 센서 없음 처리
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.no_sensor))
                views.setTextViewText(R.id.text_altitude_change, "")
                views.setTextViewText(R.id.text_action, "")
                manager.updateAppWidget(widgetId, views)
                return
            }

            if (currentAltitude == INVALID_ALTITUDE) {
                // 초기화 중
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.altitude_measuring))
                views.setTextViewText(R.id.text_altitude_change, context.getString(R.string.change_no_data))
                views.setTextViewText(R.id.text_action, context.getString(R.string.action_initializing))
                manager.updateAppWidget(widgetId, views)
                return
            }

            // 현재 고도 텍스트
            val altitudeText = context.getString(R.string.altitude_format, currentAltitude)

            // 즉각 변화량 (30초)
            val immediateChange = if (prevAltitude != INVALID_ALTITUDE)
                currentAltitude - prevAltitude else 0f

            // 변화량 텍스트: 5분 누적 + 즉각
            val changeText = context.getString(
                R.string.change_accumulated_format,
                accumulatedChange,
                immediateChange
            )

            // 행동 추천 및 색상 (5분 누적 기준)
            val (actionMessage, actionColor) = getActionRecommendation(context, accumulatedChange)

            views.setTextViewText(R.id.text_altitude, altitudeText)
            views.setTextViewText(R.id.text_altitude_change, changeText)
            views.setTextViewText(R.id.text_action, actionMessage)
            views.setTextColor(R.id.text_action, actionColor)

            manager.updateAppWidget(widgetId, views)
        }

        private fun getActionRecommendation(context: Context, change: Float): Pair<String, Int> {
            val absChange = Math.abs(change)
            return when {
                absChange == 0f ->
                    Pair(context.getString(R.string.action_initializing),
                        context.getColor(android.R.color.white))
                absChange >= 50f ->
                    Pair(context.getString(R.string.action_valsalva),
                        context.getColor(android.R.color.holo_red_light))
                absChange >= 30f ->
                    Pair(context.getString(R.string.action_yawn),
                        context.getColor(android.R.color.holo_orange_light))
                absChange >= 15f ->
                    Pair(context.getString(R.string.action_drink),
                        context.getColor(android.R.color.holo_blue_light))
                else ->
                    Pair(context.getString(R.string.action_normal),
                        context.getColor(android.R.color.holo_green_light))
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
        // 서비스 시작
        val serviceIntent = Intent(context, AltitudeService::class.java)
        context.startForegroundService(serviceIntent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val serviceIntent = Intent(context, AltitudeService::class.java)
        context.startForegroundService(serviceIntent)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val serviceIntent = Intent(context, AltitudeService::class.java)
        context.stopService(serviceIntent)
    }
}
