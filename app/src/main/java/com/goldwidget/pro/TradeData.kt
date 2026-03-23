package com.goldwidget.pro

data class TradeData(
    val positionId: String,
    val symbol: String,
    val side: String,         // "BUY" or "SELL"
    val volumeLots: Double,   // cTrader API volume / 100 = lots
    val entryPrice: Double,
    val swap: Double,
    val commission: Double,
    val openTime: Long,
    val stopLoss: Double = 0.0,
    val takeProfit: Double = 0.0
) {
    /**
     * Calculates unrealized gross P&L for metals:
     * XAUUSD standard lot = 100 troy oz, so 1 lot × $1 move = $100.
     * Adjust [contractSize] if your broker uses a different specification.
     */
    fun unrealizedPnl(currentPrice: Double, contractSize: Double = 100.0): Double {
        val priceDiff = if (side == "BUY") currentPrice - entryPrice
                        else entryPrice - currentPrice
        return priceDiff * volumeLots * contractSize
    }
}
