package com.goldwidget.pro

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.util.Log
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

        // Fetch fresh data and update all widgets.
        // Fast path: fetch gold first and render immediately (gives instant timestamp feedback).
        // Slow path: then fetch WS trade data and re-render if successful.
        fun fetchAndUpdateAll(ctx: Context) {
            // Fast path — update with fresh gold + cached trades right away
            val data = GoldApiService.fetchGoldData(ctx)
            val cachedTrades = loadTradeCache(ctx)
            if (data != null) {
                updateAllWidgets(ctx, data, cachedTrades)
            } else {
                // Market closed / network failed — show cached price with its original timestamp.
                // Do NOT bump the timestamp: the price didn't actually refresh, so showing a new
                // time would be misleading (e.g. "TCP error" with a fresh-looking time).
                val cached = loadCache(ctx) ?: return
                updateAllWidgets(ctx, cached, cachedTrades)
                // Do NOT return — trades exist even when market is closed, WS must still run
            }

            // Slow path — fetch fresh trade data and re-render if WS succeeds
            // Trades can be open on weekends / outside market hours, so always run this.
            val token     = CTraderApiService.getValidToken(ctx)
            val accountId = TokenManager.getAccountId(ctx)
            Log.d("GoldWidget", "trade fetch: token=${token?.take(8) ?: "NULL"} accountId=$accountId")
            if (token != null && accountId != null) {
                val raw = CTraderApiService.getPositions(token, accountId)
                Log.d("GoldWidget", "getPositions raw=${raw?.size ?: "NULL"} lastError=${CTraderApiService.lastError}")
                val freshTrades = raw?.filter { it.symbol.contains("XAU", ignoreCase = true) }
                Log.d("GoldWidget", "freshTrades after filter=${freshTrades?.size ?: "NULL"}")
                if (freshTrades != null) {
                    val displayData = data ?: loadCache(ctx) ?: return
                    updateAllWidgets(ctx, displayData, freshTrades)
                }
                // freshTrades == null → WS failed, cached trades already shown, no overwrite
            }
        }

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
                marketClosed  = GoldApiService.isMarketClosed()
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
                    .putFloat("trade_sl",         t.stopLoss.toFloat())
                    .putFloat("trade_tp",         t.takeProfit.toFloat())
                    .putFloat("trade_swap",       t.swap.toFloat())
                    .putFloat("trade_commission", t.commission.toFloat())
                    .putInt("trade_count",        trades.size)
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
                    swap       = p.getFloat("trade_swap", 0f).toDouble(),
                    commission = p.getFloat("trade_commission", 0f).toDouble(),
                    openTime   = 0L,
                    stopLoss   = p.getFloat("trade_sl", 0f).toDouble(),
                    takeProfit = p.getFloat("trade_tp", 0f).toDouble()
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

            val pnlIds = mgr.getAppWidgetIds(ComponentName(ctx, PnlSimpleGoldWidget::class.java))
            for (id in pnlIds) mgr.updateAppWidget(id, buildPnlSimpleViews(ctx, data, trades))
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
            views.setTextViewText(R.id.tv_high, GoldApiService.formatShortPrice(data.dayHigh))
            views.setTextViewText(R.id.tv_low, GoldApiService.formatShortPrice(data.dayLow))
            views.setTextViewText(R.id.tv_updated, "Updated " + GoldApiService.formatTime(data.timestamp))
            views.setOnClickPendingIntent(R.id.btn_refresh, TradeGoldWidget.refreshPendingIntent(ctx))
            views.setViewVisibility(R.id.iv_market_closed, if (data.marketClosed) View.VISIBLE else View.GONE)

            if (trades.isNotEmpty()) {
                val t = trades[0]
                val livePrice = if (CTraderApiService.lastLiveBid > 0) CTraderApiService.lastLiveBid else data.price
                val pnl = t.unrealizedPnl(livePrice) + t.swap + t.commission
                val pnlSign = if (pnl >= 0) "+" else ""
                val pnlColor = if (pnl >= 0) ctx.getColor(R.color.price_up)
                               else ctx.getColor(R.color.price_down)
                val sideColor = if (t.side == "BUY") ctx.getColor(R.color.price_up)
                                else ctx.getColor(R.color.price_down)
                val countSuffix = if (trades.size > 1) " (+${trades.size - 1})" else ""

                // Header pill → net P&L
                val pnlPillRes = if (pnl >= 0) R.drawable.pill_up else R.drawable.pill_down
                views.setInt(R.id.tv_change, "setBackgroundResource", pnlPillRes)
                views.setTextViewText(R.id.tv_change, "$pnlSign${"%.2f".format(pnl)}")
                views.setTextColor(R.id.tv_change, pnlColor)

                // % slot → ±% from entry price, coloured same as P&L
                val entryPct = if (t.entryPrice > 0) (livePrice - t.entryPrice) / t.entryPrice * 100.0 else 0.0
                val pctSign = if (entryPct >= 0) "+" else ""
                views.setTextViewText(R.id.tv_sl, "$pctSign${"%.2f".format(entryPct)}%")
                views.setTextColor(R.id.tv_sl, pnlColor)

                // SL slot → SL price
                views.setTextViewText(R.id.tv_tp, if (t.stopLoss > 0) GoldApiService.formatShortPrice(t.stopLoss) else "----")

                views.setTextViewText(R.id.tv_trade_side,   t.side)
                views.setTextColor(R.id.tv_trade_side, sideColor)
                views.setTextViewText(R.id.tv_trade_symbol, " ${t.symbol}$countSuffix")
                views.setTextViewText(R.id.tv_trade_lots,   "${"%.2f".format(t.volumeLots)} lots")
                views.setTextViewText(R.id.tv_trade_entry,  GoldApiService.formatPrice(t.entryPrice))
                views.setInt(R.id.root_layout, "setBackgroundResource", pnlBgDrawable(t, livePrice))

                // TP slot → TP price
                val dim = 0x60FFFFFF.toInt()
                views.setTextViewText(R.id.tv_trade_pnl, if (t.takeProfit > 0) GoldApiService.formatShortPrice(t.takeProfit) else "----")
                views.setTextColor(R.id.tv_trade_pnl, if (t.takeProfit > 0) 0xFFCCCCCC.toInt() else dim)
            } else {
                val dim = 0x60FFFFFF.toInt()
                // No trade → show --- in the change pill (don't show day % change)
                views.setInt(R.id.tv_change, "setBackgroundResource", R.drawable.pill_closed)
                views.setTextViewText(R.id.tv_change, "---")
                views.setTextColor(R.id.tv_change, dim)
                views.setViewVisibility(R.id.iv_market_closed, if (data.marketClosed) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.tv_sl, "----")
                views.setTextColor(R.id.tv_sl, dim)
                views.setTextViewText(R.id.tv_tp, "----")
                views.setTextViewText(R.id.tv_trade_side,   "----")
                views.setTextColor(R.id.tv_trade_side, dim)
                views.setTextViewText(R.id.tv_trade_symbol, "")
                views.setTextViewText(R.id.tv_trade_lots,   "")
                views.setTextViewText(R.id.tv_trade_entry,  "----")
                views.setTextViewText(R.id.tv_trade_pnl,    "----")
                views.setTextColor(R.id.tv_trade_pnl, dim)
                views.setInt(R.id.root_layout, "setBackgroundResource", R.drawable.widget_background)
            }
            return views
        }

        fun buildPnlSimpleViews(ctx: Context, data: GoldData, trades: List<TradeData>): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_pnl_simple)
            views.setTextViewText(R.id.tv_price, GoldApiService.formatPrice(data.price))
            views.setViewVisibility(
                R.id.iv_market_closed,
                if (data.marketClosed) View.VISIBLE else View.GONE
            )
            views.setTextViewText(R.id.tv_updated, GoldApiService.formatTime(data.timestamp))
            views.setOnClickPendingIntent(R.id.btn_refresh, PnlSimpleGoldWidget.refreshPendingIntent(ctx))

            if (trades.isNotEmpty()) {
                val t = trades[0]
                val livePrice = if (CTraderApiService.lastLiveBid > 0) CTraderApiService.lastLiveBid else data.price
                val pnl = t.unrealizedPnl(livePrice) + t.swap + t.commission
                val pnlSign = if (pnl >= 0) "+" else ""
                val pnlStr  = "$pnlSign${"%.2f".format(pnl)}"
                val pnlColor = if (pnl >= 0) ctx.getColor(R.color.price_up)
                               else ctx.getColor(R.color.price_down)
                val pillRes  = if (pnl >= 0) R.drawable.pill_up else R.drawable.pill_down

                views.setInt(R.id.tv_change, "setBackgroundResource", pillRes)
                views.setTextViewText(R.id.tv_change, pnlStr)
                views.setTextColor(R.id.tv_change, pnlColor)
                views.setInt(R.id.root_layout, "setBackgroundResource", pnlBgDrawable(t, livePrice))
            } else {
                views.setInt(R.id.tv_change, "setBackgroundResource", R.drawable.pill_up)
                views.setTextViewText(R.id.tv_change, "--")
                views.setTextColor(R.id.tv_change, Color.argb(120, 255, 255, 255))
                views.setInt(R.id.root_layout, "setBackgroundResource", R.drawable.widget_background)
            }
            return views
        }

        // ── PnL background drawable helpers ──────────────────────────────

        private fun pnlBgDrawable(trade: TradeData, price: Double): Int {
            val sl = trade.stopLoss
            val tp = trade.takeProfit
            if (sl <= 0.0 || tp <= 0.0) return R.drawable.widget_background

            val ratio = if (trade.side == "BUY") (price - sl) / (tp - sl)
                        else                      (sl - price) / (sl - tp)

            return when {
                ratio < 0.2  -> R.drawable.widget_bg_pnl_red_strong
                ratio < 0.4  -> R.drawable.widget_bg_pnl_red_light
                ratio < 0.6  -> R.drawable.widget_background
                ratio < 0.8  -> R.drawable.widget_bg_pnl_green_light
                else         -> R.drawable.widget_bg_pnl_green_strong
            }
        }
    }
}
