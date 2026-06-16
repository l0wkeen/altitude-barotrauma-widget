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
        const val KEY_HAS_SENSOR = "has_sensor"
        const val KEY_IS_WARMING_UP = "is_warming_up"
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
            val accumulatedChange = prefs.getFloat(KEY_ACCUMULATED_CHANGE, 0f)
            val isWarmingUp = prefs.getBoolean(KEY_IS_WARMING_UP, true)

            val views = RemoteViews(context.packageName, R.layout.widget_altitude)

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
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
                getActionRecommendation(context, accumulatedChange)
            }

            views.setTextViewText(R.id.text_altitude, altitudeText)
            views.setTextViewText(R.id.text_altitude_change, changeText)
            views.setTextViewText(R.id.text_action, actionMessage)
            views.setTextColor(R.id.text_action, actionColor)

            manager.updateAppWidget(widgetId, views)
        }

        private fun getActionRecommendation(context: Context, change: Float): Pair<String, Int> {
            val absChange = abs(change)
            return when {
                absChange < 15f ->
                    Pair(context.getString(R.string.action_normal),
                        context.getColor(android.R.color.holo_green_light))
                absChange >= 50f ->
                    Pair(context.getString(R.string.action_valsalva),
                        context.getColor(android.R.color.holo_red_light))
                absChange >= 30f ->
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
