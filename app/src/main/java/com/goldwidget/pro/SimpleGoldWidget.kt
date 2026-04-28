package com.goldwidget.pro

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.work.*

class SimpleGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        scheduleAlarm(ctx) // reboot recovery
        val cached = WidgetUpdateWorker.loadCache(ctx)
        if (cached == null) {
            // No cache — first-ever add. Show placeholder + fetch via direct Thread
            // (bypasses MIUI WorkManager scheduling delays).
            val placeholder = RemoteViews(ctx.packageName, R.layout.widget_simple)
            placeholder.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent(ctx))
            for (id in ids) mgr.updateAppWidget(id, placeholder)
            val result = goAsync()
            Thread {
                try {
                    WidgetUpdateWorker.fetchAndUpdateAll(ctx)
                } finally {
                    result.finish()
                }
            }.start()
            return
        }
        // Cache exists. Always render from cache — MIUI blanks the widget if onUpdate returns
        // without calling updateAppWidget. Rendering the same cached data causes no visible
        // flicker since the content is identical to what's already displayed.
        for (id in ids) mgr.updateAppWidget(id, WidgetUpdateWorker.buildSimpleViews(ctx, cached))
    }

    override fun onEnabled(ctx: Context) {
        scheduleAlarm(ctx)
        triggerRefresh(ctx)
    }

    override fun onDisabled(ctx: Context) {
        cancelAlarm(ctx)
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH || intent.action == ACTION_ALARM) {
            val result = goAsync()
            Thread {
                try {
                    WidgetUpdateWorker.fetchAndUpdateAll(ctx)
                } finally {
                    result.finish()
                }
            }.start()
        }
    }

    companion object {
        const val WORK_NAME      = "gold_widget_refresh"
        const val ACTION_REFRESH = "com.goldwidget.pro.ACTION_REFRESH"
        const val ACTION_ALARM   = "com.goldwidget.pro.ACTION_ALARM"
        private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L

        fun refreshPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, SimpleGoldWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun triggerRefresh(ctx: Context) {
            val work = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "gold_refresh_once", ExistingWorkPolicy.KEEP, work
            )
        }

        fun scheduleAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                ALARM_INTERVAL_MS,
                alarmPendingIntent(ctx)
            )
        }

        fun cancelAlarm(ctx: Context) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(alarmPendingIntent(ctx))
        }

        fun alarmPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, SimpleGoldWidget::class.java).apply {
                action = ACTION_ALARM
            }
            return PendingIntent.getBroadcast(
                ctx, 10, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
