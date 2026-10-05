package com.ageinmotion.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Real-time ticking engine.
 *
 * Why a foreground service instead of per-second alarms: exact alarms are
 * throttled in Doze and stripped by OEM task killers, so "live mode" over
 * AlarmManager can never be truly live. A foreground service:
 *  - runs at visible priority (OEMs leave it alone once background-running
 *    is allowed, which the setup flow requests),
 *  - can listen to SCREEN_ON/OFF (manifest receivers cannot), so it ticks
 *    ONLY while the screen is on — real-time while you look, zero cost
 *    while you don't, no wake locks held ever,
 *  - sleeps adaptively via [AgeUtils.msUntilTextChange]: at 9 decimals it
 *    redraws ~4x/sec, at 6 decimals every ~31s, at 2 decimals almost never.
 */
class TickerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(tickLoop)
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (!screenOn) {
                        screenOn = true
                        handler.removeCallbacks(tickLoop)
                        handler.post(tickLoop)
                    }
                }
            }
        }
    }

    /**
     * Crash-proof by construction: EVERY exit path re-arms the loop (or stops
     * the service cleanly). An uncaught throw here used to kill ticking
     * silently while the service still looked "running" — the classic
     * "widget just froze" failure. Floor of 1s: launcher processes coalesce
     * faster pushes anyway, and 1Hz is exactly the "seconds meter" feel.
     */
    private val tickLoop = object : Runnable {
        override fun run() {
            var delay = LIVE_FLOOR_MS
            try {
                val s = AgeWidgetRenderer.settings(this@TickerService)
                if (!s.live || !AgeWidgetRenderer.hasWidgets(this@TickerService)) {
                    stopSelf()
                    return
                }
                delay = AgeWidgetRenderer.liveTick(this@TickerService, s)
            } catch (_: Exception) {
                delay = RETRY_MS
            } finally {
                if (isRunning) {
                    try {
                        handler.postDelayed(this, delay)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        registerScreenReceiver()
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isRunning = true
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgeWidgetRenderer.prefs(this).edit()
                .putBoolean(AgeWidgetRenderer.KEY_LIVE, false).apply()
            AgeWidgetRenderer.updateAll(this)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tickLoop)
        if (screenOn) handler.post(tickLoop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickLoop)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        isRunning = false
        super.onDestroy()
    }

    /**
     * Swipe-away from recents is the #1 engine killer on OnePlus/Oppo.
     * This runs in our process just before death: arm an immediate revive
     * tick and attempt an in-process restart (allowed from here on most
     * builds; if denied, the minute base alarm + next app open revive us).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val s = AgeWidgetRenderer.settings(this)
            if (s.live && AgeWidgetRenderer.hasWidgets(this)) {
                AgeWidgetRenderer.schedule(this, s)
                try {
                    startForegroundService(Intent(this, TickerService::class.java))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.notif_channel_desc) },
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, ConfigActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 11,
            Intent(this, TickerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tick)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(
                R.drawable.ic_tick,
                getString(R.string.notif_stop),
                stop,
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ageinmotion.widget.ACTION_STOP_LIVE"
        private const val CHANNEL_ID = "live_ticker"
        private const val NOTIF_ID = 41

        /** Launcher-friendly tick floor: ~8fps looks continuous, faster gets coalesced. */
        const val LIVE_FLOOR_MS = 120L
        private const val RETRY_MS = 2000L

        /** True while the service object exists in this process. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Starts (or re-pokes) the engine. From background contexts on
         * Android 12+ this can throw — callers must catch and fall back to
         * [AgeWidgetRenderer.renderNow].
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TickerService::class.java))
            isRunning = true
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TickerService::class.java))
            } catch (_: Exception) {
            }
            isRunning = false
        }
    }
}
