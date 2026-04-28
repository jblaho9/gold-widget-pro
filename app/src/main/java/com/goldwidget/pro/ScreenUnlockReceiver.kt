package com.goldwidget.pro

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT || intent.action == Intent.ACTION_SCREEN_ON) {
            val prefs = ctx.getSharedPreferences("gold_widget", Context.MODE_PRIVATE)
            val lastFetch = prefs.getLong("last_fetch_ts", 0L)
            if (System.currentTimeMillis() - lastFetch >= 60_000L) {
                // Schedule an immediate alarm (500 ms) rather than doing the work inline.
                //
                // Why: AlarmManager holds a system-level WakeLock for the full duration of
                // the broadcast (until goAsync result.finish() is called). This prevents
                // aggressive battery optimisation (MIUI etc.) from killing the process
                // mid-fetch. Both detached threads and WorkManager lack this guarantee and
                // are killed before the 15-18s fetch completes.
                //
                // The alarm fires ACTION_ALARM to SimpleGoldWidget.onReceive, which is the
                // same proven goAsync+Thread path used by the 5-minute periodic alarm.
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 500L,
                    SimpleGoldWidget.alarmPendingIntent(ctx)
                )
            }
        }
    }
}
