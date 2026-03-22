package com.goldwidget.pro

data class GoldData(
    val price: Double,       // mid = (bid + ask) / 2
    val bid: Double,
    val ask: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val open: Double,
    val previousClose: Double,
    val changePercent: Double,
    val timestamp: Long,
    val marketClosed: Boolean
)
