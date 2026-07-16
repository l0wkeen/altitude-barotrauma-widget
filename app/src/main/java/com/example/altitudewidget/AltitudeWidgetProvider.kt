package com.example.altitudewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import kotlin.math.abs

class AltitudeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PREFS_NAME = "AltitudePrefs"
        const val KEY_ALTITUDE = "current_altitude"
        const val KEY_ACCUMULATED_CHANGE = "accumulated_change"
        const val KEY_IMMEDIATE_CHANGE = "immediate_change"
        const val KEY_HAS_SENSOR = "has_sensor"
        const val KEY_IS_WARMING_UP = "is_warming_up"
        const val KEY_SENSOR_STALLED = "sensor_stalled"
        const val KEY_PERSONAL_THRESHOLD = "personal_threshold"
        // 표시용 고도는 KEY_ALTITUDE(보정 적용값), 보정 계산용 원시 고도는 KEY_ALTITUDE_RAW,
        // 보정 오프셋(실제 고도 − 원시 고도)은 KEY_ALTITUDE_OFFSET에 저장한다.
        const val KEY_ALTITUDE_RAW = "current_altitude_raw"
        const val KEY_ALTITUDE_OFFSET = "altitude_offset"
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
            val sensorStalled = prefs.getBoolean(KEY_SENSOR_STALLED, false)
            val currentAltitude = prefs.getFloat(KEY_ALTITUDE, INVALID_ALTITUDE)
            val accumulatedChange = prefs.getFloat(KEY_ACCUMULATED_CHANGE, 0f)
            val isWarmingUp = prefs.getBoolean(KEY_IS_WARMING_UP, true)

            val views = RemoteViews(context.packageName, R.layout.widget_altitude)

            val pendingIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            if (!hasSensor) {
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.no_sensor))
                views.setTextViewText(R.id.text_altitude_change, "")
                views.setTextViewText(R.id.text_action, "")
                manager.updateAppWidget(widgetId, views)
                return
            }

            // 등록된 센서에서 일정 시간 이상 값이 오지 않는 경우(배터리 최적화, Non-wakeup
            // 센서 제한 등) — 조용히 멈춘 상태로 두지 않고 원인을 알 수 있게 안내한다.
            if (sensorStalled) {
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.sensor_stalled_altitude))
                views.setTextViewText(R.id.text_altitude_change, "")
                views.setTextViewText(R.id.text_action, context.getString(R.string.sensor_stalled_action))
                views.setTextColor(R.id.text_action, context.getColor(android.R.color.holo_orange_light))
                manager.updateAppWidget(widgetId, views)
                return
            }

            if (currentAltitude == INVALID_ALTITUDE) {
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.altitude_measuring))
                views.setTextViewText(R.id.text_altitude_change, context.getString(R.string.change_no_data))
                views.setTextViewText(R.id.text_action, context.getString(R.string.action_initializing))
                views.setTextColor(R.id.text_action, context.getColor(android.R.color.darker_gray))
                manager.updateAppWidget(widgetId, views)
                return
            }

            val altitudeText = context.getString(R.string.altitude_format, currentAltitude)

            val changeText = if (isWarmingUp) {
                context.getString(R.string.action_initializing)
            } else {
                context.getString(R.string.change_accumulated_format, accumulatedChange)
            }

            val (actionMessage, actionColor) = if (isWarmingUp) {
                Pair(context.getString(R.string.action_initializing),
                    context.getColor(android.R.color.darker_gray))
            } else {
                val personalThreshold = prefs.getFloat(KEY_PERSONAL_THRESHOLD, AlertThresholds.LEVEL1_DEFAULT_MPM)
                getActionRecommendation(context, accumulatedChange, personalThreshold)
            }

            views.setTextViewText(R.id.text_altitude, altitudeText)
            views.setTextViewText(R.id.text_altitude_change, changeText)
            views.setTextViewText(R.id.text_action, actionMessage)
            views.setTextColor(R.id.text_action, actionColor)

            manager.updateAppWidget(widgetId, views)
        }

        private fun getActionRecommendation(
            context: Context,
            change: Float,
            personalThreshold: Float
        ): Pair<String, Int> {
            val absChange = abs(change)
            return when {
                absChange < personalThreshold ->
                    Pair(context.getString(R.string.action_normal),
                        context.getColor(android.R.color.holo_green_light))
                absChange >= AlertThresholds.LEVEL3_MPM ->
                    Pair(context.getString(R.string.action_valsalva),
                        context.getColor(android.R.color.holo_red_light))
                absChange >= AlertThresholds.LEVEL2_MPM ->
                    Pair(context.getString(R.string.action_yawn),
                        context.getColor(android.R.color.holo_orange_light))
                else ->
                    Pair(context.getString(R.string.action_drink),
                        context.getColor(android.R.color.holo_blue_light))
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
        // onUpdate는 시스템 브로드캐스트 컨텍스트 → startForegroundService 직접 호출 가능
        context.startForegroundService(Intent(context, AltitudeService::class.java))
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.startForegroundService(Intent(context, AltitudeService::class.java))
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.stopService(Intent(context, AltitudeService::class.java))
    }
}
