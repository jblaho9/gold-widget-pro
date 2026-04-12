package com.goldwidget.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class PnlSimpleGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        SimpleGoldWidget.scheduleAlarm(ctx)
        val cached = WidgetUpdateWorker.loadCache(ctx)
        if (cached == null) {
            val placeholder = RemoteViews(ctx.packageName, R.layout.widget_pnl_simple)
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
        val trades = WidgetUpdateWorker.loadTradeCache(ctx)
        for (id in ids) mgr.updateAppWidget(id, WidgetUpdateWorker.buildPnlSimpleViews(ctx, cached, trades))
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
        const val ACTION_REFRESH = "com.goldwidget.pro.ACTION_REFRESH_PNL"

        fun refreshPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, PnlSimpleGoldWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                ctx, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
