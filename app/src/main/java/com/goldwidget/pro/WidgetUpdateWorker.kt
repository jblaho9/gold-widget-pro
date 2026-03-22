package com.goldwidget.pro

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.work.Worker
import androidx.work.WorkerParameters

class WidgetUpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val data = GoldApiService.fetchGoldData(applicationContext) ?: return Result.retry()
        val trades = fetchXauTrades(applicationContext)
        updateAllWidgets(applicationContext, data, trades)
        return Result.success()
    }

    private fun fetchXauTrades(ctx: Context): List<TradeData> {
        val token     = CTraderApiService.getValidToken(ctx) ?: return emptyList()
        val accountId = TokenManager.getAccountId(ctx)       ?: return emptyList()
        return CTraderApiService.getPositions(token, accountId)
            ?.filter { it.symbol.contains("XAU", ignoreCase = true) }
            ?: emptyList()
    }

    companion object {

        private const val CACHE_PREFS = "gold_widget_cache"

        // ── Gold data cache ───────────────────────────────────────────────

        fun saveCache(ctx: Context, data: GoldData) {
            ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("price",        data.price.toFloat())
                .putFloat("change_pct",   data.changePercent.toFloat())
                .putFloat("high",         data.dayHigh.toFloat())
                .putFloat("low",          data.dayLow.toFloat())
                .putFloat("open",         data.open.toFloat())
                .putFloat("prev",         data.previousClose.toFloat())
                .putBoolean("market_closed", data.marketClosed)
                .putLong("timestamp",     data.timestamp)
                .commit()
        }

        fun loadCache(ctx: Context): GoldData? {
            val p = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            val price = p.getFloat("price", 0f).toDouble()
            if (price == 0.0) return null
            return GoldData(
                price         = price,
                bid           = price,
                ask           = price,
                dayHigh       = p.getFloat("high",  price.toFloat()).toDouble(),
                dayLow        = p.getFloat("low",   price.toFloat()).toDouble(),
                open          = p.getFloat("open",  price.toFloat()).toDouble(),
                previousClose = p.getFloat("prev",  price.toFloat()).toDouble(),
                changePercent = p.getFloat("change_pct", 0f).toDouble(),
                timestamp     = p.getLong("timestamp", System.currentTimeMillis()),
                marketClosed  = p.getBoolean("market_closed", false)
            )
        }

        // ── Trade data cache ──────────────────────────────────────────────

        fun saveTradeCache(ctx: Context, trades: List<TradeData>) {
            val edit = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            if (trades.isEmpty()) {
                edit.putBoolean("has_trade", false)
            } else {
                val t = trades[0]
                edit.putBoolean("has_trade",    true)
                    .putString("trade_id",       t.positionId)
                    .putString("trade_symbol",   t.symbol)
                    .putString("trade_side",     t.side)
                    .putFloat("trade_lots",      t.volumeLots.toFloat())
                    .putFloat("trade_entry",     t.entryPrice.toFloat())
                    .putInt("trade_count",       trades.size)
            }
            edit.apply()
        }

        fun loadTradeCache(ctx: Context): List<TradeData> {
            val p = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean("has_trade", false)) return emptyList()
            return listOf(
                TradeData(
                    positionId = p.getString("trade_id", "") ?: "",
                    symbol     = p.getString("trade_symbol", "XAUUSD") ?: "XAUUSD",
                    side       = p.getString("trade_side", "BUY") ?: "BUY",
                    volumeLots = p.getFloat("trade_lots", 0f).toDouble(),
                    entryPrice = p.getFloat("trade_entry", 0f).toDouble(),
                    swap       = 0.0,
                    commission = 0.0,
                    openTime   = 0L
                )
            )
        }

        // ── Widget update ─────────────────────────────────────────────────

        fun updateAllWidgets(ctx: Context, data: GoldData, trades: List<TradeData> = emptyList()) {
            saveCache(ctx, data)
            saveTradeCache(ctx, trades)
            val mgr = AppWidgetManager.getInstance(ctx)

            val simpleIds = mgr.getAppWidgetIds(ComponentName(ctx, SimpleGoldWidget::class.java))
            for (id in simpleIds) mgr.updateAppWidget(id, buildSimpleViews(ctx, data))

            val detailedIds = mgr.getAppWidgetIds(ComponentName(ctx, DetailedGoldWidget::class.java))
            for (id in detailedIds) mgr.updateAppWidget(id, buildDetailedViews(ctx, data))

            val tradeIds = mgr.getAppWidgetIds(ComponentName(ctx, TradeGoldWidget::class.java))
            for (id in tradeIds) mgr.updateAppWidget(id, buildTradeViews(ctx, data, trades))
        }

        // ── View builders ─────────────────────────────────────────────────

        private fun changeColor(ctx: Context, pct: Double): Int =
            if (pct >= 0) ctx.getColor(R.color.price_up) else ctx.getColor(R.color.price_down)

        private fun applyChangePill(ctx: Context, views: RemoteViews, data: GoldData) {
            val pillRes = if (data.changePercent >= 0) R.drawable.pill_up else R.drawable.pill_down
            views.setInt(R.id.tv_change, "setBackgroundResource", pillRes)
            views.setTextViewText(R.id.tv_change, GoldApiService.formatChangePct(data.changePercent))
            views.setTextColor(R.id.tv_change, changeColor(ctx, data.changePercent))
            views.setViewVisibility(
                R.id.iv_market_closed,
                if (data.marketClosed) View.VISIBLE else View.GONE
            )
        }

        fun buildSimpleViews(ctx: Context, data: GoldData): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_simple)
            views.setTextViewText(R.id.tv_price, GoldApiService.formatPrice(data.price))
            applyChangePill(ctx, views, data)
            views.setTextViewText(R.id.tv_updated, GoldApiService.formatTime(data.timestamp))
            views.setOnClickPendingIntent(R.id.btn_refresh, SimpleGoldWidget.refreshPendingIntent(ctx))
            return views
        }

        fun buildDetailedViews(ctx: Context, data: GoldData): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_detailed)
            views.setTextViewText(R.id.tv_price, GoldApiService.formatPrice(data.price))
            applyChangePill(ctx, views, data)
            views.setTextViewText(R.id.tv_high, GoldApiService.formatShortPrice(data.dayHigh))
            views.setTextViewText(R.id.tv_low, GoldApiService.formatShortPrice(data.dayLow))
            views.setTextViewText(R.id.tv_open, GoldApiService.formatShortPrice(data.open))
            views.setTextViewText(R.id.tv_prev_close, GoldApiService.formatShortPrice(data.previousClose))
            views.setTextViewText(R.id.tv_updated, "Updated " + GoldApiService.formatTime(data.timestamp))
            views.setOnClickPendingIntent(R.id.btn_refresh, DetailedGoldWidget.refreshPendingIntent(ctx))
            return views
        }

        fun buildTradeViews(ctx: Context, data: GoldData, trades: List<TradeData>): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_trade)
            views.setTextViewText(R.id.tv_price, GoldApiService.formatPrice(data.price))
            applyChangePill(ctx, views, data)
            views.setTextViewText(R.id.tv_high, GoldApiService.formatShortPrice(data.dayHigh))
            views.setTextViewText(R.id.tv_low, GoldApiService.formatShortPrice(data.dayLow))
            views.setTextViewText(R.id.tv_open, GoldApiService.formatShortPrice(data.open))
            views.setTextViewText(R.id.tv_prev_close, GoldApiService.formatShortPrice(data.previousClose))
            views.setTextViewText(R.id.tv_updated, "Updated " + GoldApiService.formatTime(data.timestamp))
            views.setOnClickPendingIntent(R.id.btn_refresh, TradeGoldWidget.refreshPendingIntent(ctx))

            if (trades.isNotEmpty()) {
                val t = trades[0]
                val pnl = t.unrealizedPnl(data.price)
                val pnlSign = if (pnl >= 0) "+" else ""
                val pnlStr = "$pnlSign${"%.2f".format(pnl)}"
                val pnlColor = if (pnl >= 0) ctx.getColor(R.color.price_up)
                               else ctx.getColor(R.color.price_down)
                val sideColor = if (t.side == "BUY") ctx.getColor(R.color.price_up)
                                else ctx.getColor(R.color.price_down)

                val countSuffix = if (trades.size > 1) " (+${trades.size - 1})" else ""

                views.setViewVisibility(R.id.ll_trade,    View.VISIBLE)
                views.setViewVisibility(R.id.ll_no_trade, View.GONE)

                views.setTextViewText(R.id.tv_trade_side,   t.side)
                views.setTextColor(R.id.tv_trade_side, sideColor)
                views.setTextViewText(R.id.tv_trade_symbol, " ${t.symbol}$countSuffix")
                views.setTextViewText(R.id.tv_trade_lots,   "${"%.2f".format(t.volumeLots)} lots")
                views.setTextViewText(R.id.tv_trade_entry,  GoldApiService.formatPrice(t.entryPrice))
                views.setTextViewText(R.id.tv_trade_pnl,    pnlStr)
                views.setTextColor(R.id.tv_trade_pnl, pnlColor)
            } else {
                views.setViewVisibility(R.id.ll_trade,    View.GONE)
                views.setViewVisibility(R.id.ll_no_trade, View.VISIBLE)
                val connected = TokenManager.hasValidToken(ctx)
                views.setTextViewText(
                    R.id.tv_no_trade,
                    if (connected) "No open XAUUSD trades"
                    else "Open app to connect cTrader"
                )
            }
            return views
        }
    }
}
