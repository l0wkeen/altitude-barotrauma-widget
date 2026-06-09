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
import androidx.core.content.ContextCompat

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

            // 위젯 클릭 시 앱 실행
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            if (!hasSensor) {
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.no_sensor))
                views.setTextViewText(R.id.text_altitude_change, "")
                views.setTextViewText(R.id.text_action, "")
                manager.updateAppWidget(widgetId, views)
                return
            }

            if (currentAltitude == INVALID_ALTITUDE) {
                views.setTextViewText(R.id.text_altitude, context.getString(R.string.altitude_measuring))
                views.setTextViewText(R.id.text_altitude_change, context.getString(R.string.change_no_data))
                views.setTextViewText(R.id.text_action, context.getString(R.string.action_initializing))
                views.setTextColor(R.id.text_action, Color.WHITE)
                manager.updateAppWidget(widgetId, views)
                return
            }

            val altitudeText = context.getString(R.string.altitude_format, currentAltitude)

            val immediateChange = if (prevAltitude != INVALID_ALTITUDE)
                currentAltitude - prevAltitude else 0f

            val changeText = context.getString(
                R.string.change_accumulated_format,
                accumulatedChange,
                immediateChange
            )

            // ✅ 수정: 명시적 타입 파라미터 + Color 리터럴 사용
            val (actionMessage, actionColor) = getActionRecommendation(context, accumulatedChange)

            views.setTextViewText(R.id.text_altitude, altitudeText)
            views.setTextViewText(R.id.text_altitude_change, changeText)
            views.setTextViewText(R.id.text_action, actionMessage)
            views.setTextColor(R.id.text_action, actionColor)  // ✅ Int color 직접 전달

            manager.updateAppWidget(widgetId, views)
        }

        // ✅ 핵심 수정: 반환 타입 명시 + android.R.color 대신 Color 리터럴 사용
        private fun getActionRecommendation(context: Context, change: Float): Pair<String, Int> {
            val absChange = Math.abs(change)
            return when {
                absChange == 0f -> Pair(
                    context.getString(R.string.action_initializing),
                    Color.WHITE
                )
                absChange >= 50f -> Pair(
                    context.getString(R.string.action_valsalva),
                    Color.parseColor("#FF5252")   // 빨강
                )
                absChange >= 30f -> Pair(
                    context.getString(R.string.action_yawn),
                    Color.parseColor("#FFB74D")   // 주황
                )
                absChange >= 15f -> Pair(
                    context.getString(R.string.action_drink),
                    Color.parseColor("#64B5F6")   // 파랑
                )
                else -> Pair(
                    context.getString(R.string.action_normal),
                    Color.parseColor("#81C784")   // 초록
                )
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
        val serviceIntent = Intent(context, AltitudeService::class.java)
        context.startForegroundService(serviceIntent)
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
