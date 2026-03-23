package com.goldwidget.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class PnlSimpleGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val cachedGold   = WidgetUpdateWorker.loadCache(ctx)
        val cachedTrades = WidgetUpdateWorker.loadTradeCache(ctx)
        for (id in ids) {
            if (cachedGold != null) {
                mgr.updateAppWidget(id, WidgetUpdateWorker.buildPnlSimpleViews(ctx, cachedGold, cachedTrades))
            } else {
                val clickOnly = RemoteViews(ctx.packageName, R.layout.widget_pnl_simple)
                clickOnly.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent(ctx))
                mgr.partiallyUpdateAppWidget(id, clickOnly)
            }
        }
        SimpleGoldWidget.schedulePeriodicUpdates(ctx)
        val pending = goAsync()
        Thread {
            try {
                val data = GoldApiService.fetchGoldData(ctx)
                if (data != null) {
                    val token     = CTraderApiService.getValidToken(ctx)
                    val accountId = TokenManager.getAccountId(ctx)
                    val trades = if (token != null && accountId != null)
                        CTraderApiService.getPositions(token, accountId)
                            ?.filter { it.symbol.contains("XAU", ignoreCase = true) }
                            ?: emptyList()
                    else emptyList()
                    WidgetUpdateWorker.updateAllWidgets(ctx, data, trades)
                }
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
                    if (data != null) {
                        val token     = CTraderApiService.getValidToken(ctx)
                        val accountId = TokenManager.getAccountId(ctx)
                        val trades = if (token != null && accountId != null)
                            CTraderApiService.getPositions(token, accountId)
                                ?.filter { it.symbol.contains("XAU", ignoreCase = true) }
                                ?: emptyList()
                        else emptyList()
                        WidgetUpdateWorker.updateAllWidgets(ctx, data, trades)
                    }
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
