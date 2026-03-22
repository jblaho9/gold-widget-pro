package com.goldwidget.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class DetailedGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val cached = WidgetUpdateWorker.loadCache(ctx)
        for (id in ids) {
            if (cached != null) {
                mgr.updateAppWidget(id, WidgetUpdateWorker.buildDetailedViews(ctx, cached))
            } else {
                val clickOnly = RemoteViews(ctx.packageName, R.layout.widget_detailed)
                clickOnly.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent(ctx))
                mgr.partiallyUpdateAppWidget(id, clickOnly)
            }
        }
        SimpleGoldWidget.schedulePeriodicUpdates(ctx)
        val pending = goAsync()
        Thread {
            try {
                val data = GoldApiService.fetchGoldData(ctx)
                if (data != null) WidgetUpdateWorker.updateAllWidgets(ctx, data)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onEnabled(ctx: Context) {
        SimpleGoldWidget.schedulePeriodicUpdates(ctx)
        SimpleGoldWidget.triggerRefresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val result = goAsync()
            Thread {
                try {
                    val data = GoldApiService.fetchGoldData(ctx)
                    if (data != null) WidgetUpdateWorker.updateAllWidgets(ctx, data)
                } finally {
                    result.finish()
                }
            }.start()
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.goldwidget.pro.ACTION_REFRESH_DETAILED"

        fun refreshPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, DetailedGoldWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                ctx, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
