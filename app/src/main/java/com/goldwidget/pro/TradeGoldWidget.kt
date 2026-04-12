package com.goldwidget.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class TradeGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        SimpleGoldWidget.scheduleAlarm(ctx)
        val cached = WidgetUpdateWorker.loadCache(ctx)
        if (cached == null) {
            for (id in ids) mgr.updateAppWidget(id, buildPlaceholderViews(ctx))
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
        val trades = WidgetUpdateWorker.loadTradeCache(ctx)
        for (id in ids) mgr.updateAppWidget(id, WidgetUpdateWorker.buildTradeViews(ctx, cached, trades))
        // Fetch fresh data in background so new/closed trades are picked up promptly
        val result = goAsync()
        Thread {
            try {
                WidgetUpdateWorker.fetchAndUpdateAll(ctx)
            } finally {
                result.finish()
            }
        }.start()
    }

    override fun onEnabled(ctx: Context) {
        SimpleGoldWidget.scheduleAlarm(ctx)
        SimpleGoldWidget.triggerRefresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
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
        const val ACTION_REFRESH = "com.goldwidget.pro.ACTION_REFRESH_TRADE"

        fun buildPlaceholderViews(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_trade)
            v.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent(ctx))
            return v
        }

        fun refreshPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, TradeGoldWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                ctx, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
