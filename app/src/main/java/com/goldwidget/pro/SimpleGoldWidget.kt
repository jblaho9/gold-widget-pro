package com.goldwidget.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.*
import java.util.concurrent.TimeUnit

class SimpleGoldWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val cached = WidgetUpdateWorker.loadCache(ctx)
        for (id in ids) {
            if (cached != null) {
                mgr.updateAppWidget(id, WidgetUpdateWorker.buildSimpleViews(ctx, cached))
            } else {
                val clickOnly = RemoteViews(ctx.packageName, R.layout.widget_simple)
                clickOnly.setOnClickPendingIntent(R.id.btn_refresh, refreshPendingIntent(ctx))
                mgr.partiallyUpdateAppWidget(id, clickOnly)
            }
        }
        // Fetch directly on a thread — avoids MIUI WorkManager scheduling delays
        val pending = goAsync()
        Thread {
            try {
                val data = GoldApiService.fetchGoldData(ctx)
                if (data != null) {
                    val trades = WidgetUpdateWorker.loadTradeCache(ctx)
                    WidgetUpdateWorker.updateAllWidgets(ctx, data, trades)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onEnabled(ctx: Context) {
        schedulePeriodicUpdates(ctx)
        triggerRefresh(ctx)
    }

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val result = goAsync()
            Thread {
                try {
                    val data = GoldApiService.fetchGoldData(ctx)
                    if (data != null) {
                        val token = CTraderApiService.getValidToken(ctx)
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
        const val WORK_NAME    = "gold_widget_refresh"
        const val ACTION_REFRESH = "com.goldwidget.pro.ACTION_REFRESH"

        fun refreshPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, SimpleGoldWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun triggerRefresh(ctx: Context) {
            val work = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "gold_refresh_once", ExistingWorkPolicy.KEEP, work
            )
        }

        fun schedulePeriodicUpdates(ctx: Context) {
            val periodic = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic
            )
        }
    }
}
