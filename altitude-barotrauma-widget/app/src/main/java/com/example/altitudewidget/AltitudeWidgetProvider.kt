package com.example.altitudewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.widget.RemoteViews

/**
 * Ear Barotrauma 예방 고도 변화 측정 위젯
 *
 * 개선 사항:
 * - 5분 누적 변화량(KEY_ACCUMULATED_CHANGE) 기반 위험도 판단
 * - 기압 센서 없는 기기 "센서 없음" 메시지 표시
 * - 행동 추천 문자열 strings.xml 분리
 * - 위험 단계별 텍스트 색상 변경
 */
class AltitudeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PREFS_NAME               = "AltitudePrefs"
        const val KEY_PREV_ALTITUDE        = "prev_altitude"
        const val KEY_CURRENT_ALTITUDE     = "current_altitude"
        const val KEY_ACCUMULATED_CHANGE   = "accumulated_change_5min"
        const val KEY_SENSOR_AVAILABLE     = "sensor_available"
        const val KEY_HISTORY_ALT          = "history_alt_"
        const val KEY_HISTORY_TIME         = "history_time_"

        // 위험 단계 임계값 (m) - 5분 누적 기준
        const val THRESHOLD_WARN   = 50f   // ⚠️ 발살바
        const val THRESHOLD_MEDIUM = 30f   // 하품/침 삼키기
        const val THRESHOLD_MILD   = 15f   // 물 마시기

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AltitudeWidgetProvider::class.java)
            )
            for (id in ids) updateWidgetView(context, manager, id)
        }

        fun updateWidgetView(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val views = RemoteViews(context.packageName, R.layout.widget_altitude)

            // 기압 센서 없는 기기 처리
            val sensorAvailable = prefs.getBoolean(KEY_SENSOR_AVAILABLE, true)
            if (!sensorAvailable) {
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.error_no_sensor))
                views.setTextViewText(R.id.text_altitude_change, "")
                views.setTextViewText(R.id.text_action, "")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val currentAltitude: Float? =
                if (prefs.contains(KEY_CURRENT_ALTITUDE))
                    prefs.getFloat(KEY_CURRENT_ALTITUDE, 0f) else null

            val prevAltitude: Float? =
                if (prefs.contains(KEY_PREV_ALTITUDE))
                    prefs.getFloat(KEY_PREV_ALTITUDE, 0f) else null

            val accumulatedChange: Float? =
                if (prefs.contains(KEY_ACCUMULATED_CHANGE))
                    prefs.getFloat(KEY_ACCUMULATED_CHANGE, 0f) else null

            // 즉각 변화량 (직전 30초)
            val immediateChange: Float? =
                if (currentAltitude != null && prevAltitude != null)
                    currentAltitude - prevAltitude else null

            // 현재 고도 표시
            val altitudeText = currentAltitude
                ?.let { context.getString(R.string.altitude_format, it) }
                ?: context.getString(R.string.altitude_measuring)
            views.setTextViewText(R.id.text_altitude, altitudeText)

            // 변화량 표시 (5분 누적 / 직전 30초)
            val changeText = buildChangeText(context, accumulatedChange, immediateChange)
            views.setTextViewText(R.id.text_altitude_change, changeText)

            // 행동 추천 및 색상
            val actionMsg = getActionRecommendation(accumulatedChange, context)
            val actionColor = getRiskColor(accumulatedChange)
            views.setTextViewText(R.id.text_action, actionMsg)
            views.setTextColor(R.id.text_action, actionColor)

            // 위젯 탭 → 서비스 재시작
            val intent = Intent(context, AltitudeService::class.java)
            val pendingIntent = PendingIntent.getService(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildChangeText(
            context: Context,
            accumulated: Float?,
            immediate: Float?
        ): String {
            val accStr = accumulated
                ?.let { context.getString(R.string.change_accumulated_format, it) }
                ?: context.getString(R.string.change_no_data)
            val immStr = immediate
                ?.let { " / ${context.getString(R.string.change_immediate_format, it)}" }
                ?: ""
            return accStr + immStr
        }

        /**
         * 5분 누적 변화량 기준 ear barotrauma 예방 행동 추천
         * 양수 = 상승(기압 감소), 음수 = 하강(기압 증가)
         */
        fun getActionRecommendation(change: Float?, context: Context): String {
            if (change == null) return context.getString(R.string.action_initializing)
            return when {
                change >= THRESHOLD_WARN    -> context.getString(R.string.action_valsalva)
                change >= THRESHOLD_MEDIUM  -> context.getString(R.string.action_yawn)
                change >= THRESHOLD_MILD    -> context.getString(R.string.action_drink)
                change <= -THRESHOLD_WARN   -> context.getString(R.string.action_valsalva)
                change <= -THRESHOLD_MEDIUM -> context.getString(R.string.action_yawn)
                change <= -THRESHOLD_MILD   -> context.getString(R.string.action_drink)
                else                        -> context.getString(R.string.action_normal)
            }
        }

        /** 위험 단계에 따른 텍스트 색상 */
        private fun getRiskColor(change: Float?): Int {
            val abs = change?.let { kotlin.math.abs(it) } ?: return Color.parseColor("#888888")
            return when {
                abs >= THRESHOLD_WARN   -> Color.parseColor("#CC0000")  // 빨강 (위험)
                abs >= THRESHOLD_MEDIUM -> Color.parseColor("#E67E00")  // 주황 (주의)
                abs >= THRESHOLD_MILD   -> Color.parseColor("#1976D2")  // 파랑 (가벼운 조치)
                else                    -> Color.parseColor("#388E3C")  // 초록 (정상)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        context.startService(Intent(context, AltitudeService::class.java))
        // WorkManager로 주기적 재시작 예약
        AltitudeWorker.schedulePeriodicWork(context)
        for (id in appWidgetIds) updateWidgetView(context, appWidgetManager, id)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.startService(Intent(context, AltitudeService::class.java))
        AltitudeWorker.schedulePeriodicWork(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.stopService(Intent(context, AltitudeService::class.java))
        AltitudeWorker.cancelWork(context)
    }
}
