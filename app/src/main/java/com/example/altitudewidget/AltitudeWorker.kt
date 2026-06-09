package com.example.altitudewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

class AltitudeWorker(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        triggerMeasure(context)
        return Result.success()
    }

    companion object {
        private const val WORK_TAG = "altitude_periodic_work"
        private const val ALARM_REQUEST_CODE = 9001

        fun triggerMeasure(context: Context) {
            val intent = Intent(context, AltitudeMeasureReceiver::class.java).apply {
                action = AltitudeMeasureReceiver.ACTION_MEASURE
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send()
        }

        fun schedulePeriodicWork(context: Context) {
            // 즉시 한 번 측정
            triggerMeasure(context)

            // AlarmManager로 15분마다 반복
            val intent = Intent(context, AltitudeMeasureReceiver::class.java).apply {
                action = AltitudeMeasureReceiver.ACTION_MEASURE
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 15 * 60 * 1000L,
                15 * 60 * 1000L,
                pi
            )
        }

        fun cancelWork(context: Context) {
            val intent = Intent(context, AltitudeMeasureReceiver::class.java).apply {
                action = AltitudeMeasureReceiver.ACTION_MEASURE
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }
    }
}
