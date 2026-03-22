package com.goldwidget.pro

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks day high / low / open / prev-close in SharedPreferences using
 * the live spot price fetched each refresh. Resets automatically at midnight.
 */
object PriceHistory {

    private const val PREFS = "gold_price_history"

    data class DayStats(
        val high: Double,
        val low: Double,
        val open: Double,
        val prevClose: Double
    )

    fun update(ctx: Context, price: Double): DayStats {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = today()
        val storedDay = prefs.getString("day", "")

        return if (storedDay != today) {
            // New day: yesterday's last price becomes prevClose, price is today's open
            val prevClose = prefs.getFloat("last_price", price.toFloat()).toDouble()
            prefs.edit()
                .putString("day", today)
                .putFloat("open", price.toFloat())
                .putFloat("high", price.toFloat())
                .putFloat("low", price.toFloat())
                .putFloat("prev_close", prevClose.toFloat())
                .putFloat("last_price", price.toFloat())
                .apply()
            DayStats(high = price, low = price, open = price, prevClose = prevClose)
        } else {
            val high = maxOf(prefs.getFloat("high", price.toFloat()).toDouble(), price)
            val low = minOf(prefs.getFloat("low", price.toFloat()).toDouble(), price)
            val open = prefs.getFloat("open", price.toFloat()).toDouble()
            val prevClose = prefs.getFloat("prev_close", price.toFloat()).toDouble()
            prefs.edit()
                .putFloat("high", high.toFloat())
                .putFloat("low", low.toFloat())
                .putFloat("last_price", price.toFloat())
                .apply()
            DayStats(high = high, low = low, open = open, prevClose = prevClose)
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
