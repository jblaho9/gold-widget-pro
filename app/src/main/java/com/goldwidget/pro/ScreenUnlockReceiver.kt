package com.goldwidget.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT || intent.action == Intent.ACTION_SCREEN_ON) {
            val prefs = ctx.getSharedPreferences("gold_widget", Context.MODE_PRIVATE)
            val lastFetch = prefs.getLong("last_fetch_ts", 0L)
            if (System.currentTimeMillis() - lastFetch >= 60_000L) {
                // Enqueue via WorkManager so the work outlives the broadcast receiver's deadline.
                // goAsync() + Thread was being killed by Android's ~10s broadcast timeout before
                // the full fetch (gold HTTP + cTrader TCP) could complete (~15-18s worst case).
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "gold_unlock_refresh",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            }
        }
    }
}
