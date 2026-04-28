package com.goldwidget.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT || intent.action == Intent.ACTION_SCREEN_ON) {
            val prefs = ctx.getSharedPreferences("gold_widget", Context.MODE_PRIVATE)
            val lastFetch = prefs.getLong("last_fetch_ts", 0L)
            if (System.currentTimeMillis() - lastFetch >= 60_000L) {
                // Use a plain detached thread with applicationContext.
                //
                // goAsync()+Thread: killed by Android's broadcast deadline before the full
                //   fetch (~15-18s worst case) can complete.
                // WorkManager: wrong tool — CONNECTED constraint defers until WiFi reconnects
                //   after sleep (10-30s), and Result.retry() adds another 30s backoff.
                //   Also subject to battery-optimisation deferral on some devices.
                //
                // Detached thread: onReceive() returns immediately (broadcast considered done),
                // the thread runs freely with applicationContext which lives for the app's
                // full lifetime. No deadline, no deferral.
                val appCtx = ctx.applicationContext
                Thread {
                    WidgetUpdateWorker.fetchAndUpdateAll(appCtx)
                }.start()
            }
        }
    }
}
