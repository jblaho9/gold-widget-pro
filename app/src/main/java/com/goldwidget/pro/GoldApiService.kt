package com.goldwidget.pro

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object GoldApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Swissquote public spot feed — same source as cTrader liquidity providers
    private const val URL =
        "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD"

    fun fetchGoldData(ctx: android.content.Context): GoldData? {
        return try {
            val request = Request.Builder()
                .url(URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .addHeader("Accept", "application/json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            } ?: return null

            // Find the "prime" spread profile (tightest institutional spread)
            val arr = JSONArray(body)
            var bid = 0.0
            var ask = 0.0

            outer@ for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val profiles = entry.getJSONArray("spreadProfilePrices")
                for (j in 0 until profiles.length()) {
                    val p = profiles.getJSONObject(j)
                    if (p.getString("spreadProfile") == "prime") {
                        bid = p.getDouble("bid")
                        ask = p.getDouble("ask")
                        break@outer
                    }
                }
            }
            if (bid == 0.0) { noQuotes = true; return null }
            noQuotes = false

            val price = (bid + ask) / 2.0

            // Update tracked day stats in SharedPreferences
            val stats = PriceHistory.update(ctx, price)
            val changePct = if (stats.prevClose != 0.0)
                ((price - stats.prevClose) / stats.prevClose) * 100.0 else 0.0

            val now = System.currentTimeMillis()
            ctx.getSharedPreferences("gold_widget", android.content.Context.MODE_PRIVATE)
                .edit().putLong("last_fetch_ts", now).apply()

            GoldData(
                price = price,
                bid = bid,
                ask = ask,
                dayHigh = stats.high,
                dayLow = stats.low,
                open = stats.open,
                previousClose = stats.prevClose,
                changePercent = changePct,
                timestamp = now,
                marketClosed = isMarketClosed()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun formatPrice(price: Double): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        fmt.minimumFractionDigits = 2
        fmt.maximumFractionDigits = 2
        return fmt.format(price)
    }

    fun formatShortPrice(price: Double): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        fmt.minimumFractionDigits = 0
        fmt.maximumFractionDigits = 0
        return fmt.format(price)
    }

    fun formatChangePct(pct: Double): String {
        val sign = if (pct >= 0) "+" else ""
        return "$sign%.2f%%".format(pct)
    }

    fun formatTime(ts: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

    // Set to true when the API responds successfully but returns no bid (market closed/holiday)
    var noQuotes = false
        private set

    // XAU/USD trades Sun 5 PM ET → Fri 5 PM ET, with a daily 1-hour break 5–6 PM ET
    // Also closed on Good Friday, Christmas Day, and New Year's Day
    fun isMarketClosed(): Boolean {
        if (noQuotes) return true
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val minutesInDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val close = 17 * 60
        val reopen = 18 * 60
        val dailyBreak = minutesInDay in close until reopen
        if (isHoliday(cal)) return true
        return when (dow) {
            Calendar.SATURDAY -> true
            Calendar.SUNDAY   -> minutesInDay < close
            Calendar.FRIDAY   -> minutesInDay >= close
            else              -> dailyBreak
        }
    }

    private fun isHoliday(cal: Calendar): Boolean {
        val year  = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val day   = cal.get(Calendar.DAY_OF_MONTH)
        if (month == Calendar.JANUARY  && day == 1)  return true // New Year's Day
        if (month == Calendar.DECEMBER && day == 25) return true // Christmas
        val gf = goodFriday(year)
        return month == gf.get(Calendar.MONTH) && day == gf.get(Calendar.DAY_OF_MONTH)
    }

    // Anonymous Gregorian algorithm → Easter Sunday, then subtract 2 days
    private fun goodFriday(year: Int): Calendar {
        val a = year % 19
        val b = year / 100; val c = year % 100
        val d = b / 4;      val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4;      val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val easterMonth = (h + l - 7 * m + 114) / 31      // 3=Mar, 4=Apr
        val easterDay   = (h + l - 7 * m + 114) % 31 + 1
        return Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            set(year, easterMonth - 1, easterDay)
            add(Calendar.DAY_OF_YEAR, -2)
        }
    }
}
