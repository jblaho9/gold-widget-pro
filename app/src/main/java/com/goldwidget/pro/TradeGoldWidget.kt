package com.goldwidget.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class TradeGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val cachedGold   = WidgetUpdateWorker.loadCache(ctx)
        val cachedTrades = WidgetUpdateWorker.loadTradeCache(ctx)
        for (id in ids) {
            try {
                val views = if (cachedGold != null)
                    WidgetUpdateWorker.buildTradeViews(ctx, cachedGold, cachedTrades)
                else
                    buildPlaceholderViews(ctx)
                mgr.updateAppWidget(id, views)
            } catch (e: Exception) {
                // If anything fails, push a minimal placeholder so the launcher
                // clears the "Can't load widget" state on the next update cycle.
                try { mgr.updateAppWidget(id, buildPlaceholderViews(ctx)) } catch (_: Exception) {}
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
