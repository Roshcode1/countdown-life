package com.ageinmotion.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Shared widget engine for both the square (2x2) and wide (4x2) providers.
 *
 * Look: faithful to the web recreation — white 5x7 dot-matrix digits on the
 * Nothing-style #1c1c1c card, rendered to a bitmap (RemoteViews can't draw
 * custom views, so this is the only way to get real dot-matrix dots).
 *
 * Motion: two modes.
 *  - Battery mode (default): inexact per-minute refresh. ~zero drain.
 *  - Live mode: [TickerService] redraws (1Hz while the screen is on,
 *    slower when precision allows) with a per-minute alarm underneath as
 *    a safety net that revives it if an OEM killer ever stops it.
 *
 * Battery honesty: there are TWO independent gates on most phones —
 *  (1) the AOSP Doze exemption ([isExempted]) and (2) the OEM's own
 *  background killer ("Allow background usage" on OnePlus/Oppo etc.).
 *  The setup UI checks (1) and guides through (2) per manufacturer.
 */
object AgeWidgetRenderer {

    const val ACTION_TICK = "com.ageinmotion.widget.ACTION_TICK"

    const val PREFS = "age_prefs"
    const val KEY_DOB = "dob"
    const val KEY_DECIMALS = "decimals"
    const val KEY_GHOST = "ghost"
    const val KEY_LIVE = "live_mode"
    const val KEY_OPACITY = "card_opacity"
    const val KEY_STYLE = "style"
    const val KEY_DOT_COLOR = "dot_color"
    const val KEY_MODE = "mode"
    const val KEY_DIGIT_SIZE = "digit_size"

    const val MODE_AGE = "age"
    const val MODE_DOWN = "countdown"
    const val KEY_LAST_TICK = "last_tick"
    const val KEY_LAST_TEXT = "last_text"

    const val STYLE_DOTS = "dots"
    const val STYLE_FLIP = "flip"

    const val DEFAULT_DECIMALS = 9
    const val DEFAULT_OPACITY = 100
    const val DEFAULT_DOT_COLOR = 0xFFFFFFFF.toInt()
    const val DEFAULT_DIGIT_SIZE = 100
    const val MIN_DIGIT_SIZE = 40
    const val PLACEHOLDER = "--.---------"

    /** Preset dot colors offered in the UI. */
    val COLOR_PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFD71921.toInt(),
        0xFFFFB300.toInt(),
        0xFF00E676.toInt(),
        0xFF00E5FF.toInt(),
        0xFFB388FF.toInt(),
        0xFFFF6D00.toInt(),
    )

    data class WidgetSettings(
        val dobMillis: Long,
        val decimals: Int,
        val ghost: Boolean,
        val live: Boolean,
        val opacity: Int,
        val style: String,
        val dotColor: Int,
        val mode: String,
        val digitSize: Int,
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): WidgetSettings {
        val p = prefs(context)
        val style = p.getString(KEY_STYLE, STYLE_DOTS) ?: STYLE_DOTS
        val mode = p.getString(KEY_MODE, MODE_AGE) ?: MODE_AGE
        return WidgetSettings(
            dobMillis = p.getLong(KEY_DOB, 0L),
            decimals = p.getInt(KEY_DECIMALS, DEFAULT_DECIMALS).coerceIn(0, 10),
            ghost = p.getBoolean(KEY_GHOST, false),
            live = p.getBoolean(KEY_LIVE, false),
            opacity = p.getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100),
            style = if (style == STYLE_FLIP) STYLE_FLIP else STYLE_DOTS,
            dotColor = p.getInt(KEY_DOT_COLOR, DEFAULT_DOT_COLOR),
            mode = if (mode == MODE_DOWN) MODE_DOWN else MODE_AGE,
            digitSize = p.getInt(KEY_DIGIT_SIZE, DEFAULT_DIGIT_SIZE)
                .coerceIn(MIN_DIGIT_SIZE, 100),
        )
    }

    /** Card alpha 0-255 derived from the 0-100 opacity setting. */
    fun cardAlpha(s: WidgetSettings): Int = (s.opacity * 255 / 100).coerceIn(0, 255)

    /**
     * Adaptive countdown sizing: fewer groups → bigger digits, so a lone
     * "053" fills the card instead of rattling in a corner. Bounds keep
     * every bitmap safely under the ~1MB binder cap (pure math, tested).
     */
    fun countdownCellPx(text: String): Float =
        (1150f / DotMatrix.measureCells(text).coerceAtLeast(1)).coerceIn(11f, 28f)

    fun countdownFontPx(text: String): Float =
        (FlipClock.FONT_PX * FULL_COUNTDOWN_CHARS / text.length.coerceAtLeast(1))
            .coerceIn(FlipClock.FONT_PX, FlipClock.MAX_FONT_PX)

    private const val FULL_COUNTDOWN_CHARS = 15

    /**
     * Inner padding (dp) around the digits for a 40-100% size: 100% hugs
     * the card with 8dp, 40% floats small with 32dp. Pure math, unit-tested.
     */
    fun digitPaddingDp(size: Int): Float =
        8f + (100 - size.coerceIn(MIN_DIGIT_SIZE, 100)) * 0.4f

    /**
     * Display text for the widget / preview. In countdown mode [WidgetSettings.dobMillis]
     * is the future target timestamp (same stored anchor, new meaning).
     */
    fun widgetText(s: WidgetSettings, now: Long): String =
        if (s.dobMillis == 0L) {
            PLACEHOLDER
        } else if (s.mode == MODE_DOWN) {
            AgeUtils.formatCountdown(AgeUtils.countdownParts(now, s.dobMillis))
        } else {
            AgeUtils.formatAge(AgeUtils.ageYears(s.dobMillis, now), s.decimals)
        }

    fun renderBitmap(text: String, ghost: Boolean): Bitmap =
        DotMatrix.render(text, ghost = ghost)

    // ---- flip-animation state (process-scoped; snaps settled on restart) ---

    private var flipCur: String? = null
    private var flipPrev: String = PLACEHOLDER
    private var flipStart: Long = 0L

    /**
     * Renders one settled frame (used by alarms/provider paths — always
     * snaps the flip animation shut).
     */
    fun renderBitmap(context: Context, text: String, s: WidgetSettings): Bitmap =
        if (s.style == STYLE_FLIP) {
            val fontPx =
                if (s.mode == MODE_DOWN) countdownFontPx(text) else FlipClock.FONT_PX
            FlipClock.render(text, text, 1f, s.dotColor, FlipClock.typeface(context), fontPx)
        } else if (s.mode == MODE_DOWN) {
            DotMatrix.render(text, ghost = s.ghost, dotColor = s.dotColor, cellPx = countdownCellPx(text))
        } else {
            DotMatrix.render(text, ghost = s.ghost, dotColor = s.dotColor)
        }

    // ---- widget updates ----------------------------------------------------

    private val PROVIDERS = arrayOf(
        AgeWidgetProvider::class.java,
        AgeWidgetWideProvider::class.java,
    )

    fun allWidgetIds(context: Context): Map<Class<*>, IntArray> {
        val mgr = AppWidgetManager.getInstance(context)
        return PROVIDERS.associateWith { mgr.getAppWidgetIds(ComponentName(context, it)) }
    }

    fun hasWidgets(context: Context): Boolean =
        allWidgetIds(context).values.any { it.isNotEmpty() }

    /**
     * Renders one frame now. Writes the heartbeat ([KEY_LAST_TICK]) on every
     * run, but only pushes a bitmap to the launcher when the visible text
     * actually changed — skipping redundant binder traffic and launcher
     * redraws. Flip animations always snap shut here; the live engine uses
     * [liveTick] for mid-flip frames. Returns the current text.
     */
    fun renderNow(context: Context): String {
        val p = prefs(context)
        val s = settings(context)
        val now = System.currentTimeMillis()
        val text = widgetText(s, now)
        p.edit().putLong(KEY_LAST_TICK, now).apply()
        // Snap flip state so a restarted process never hangs mid-flip.
        flipCur = text
        flipPrev = text
        if (text == p.getString(KEY_LAST_TEXT, null)) return text

        pushFrame(context, s, text, renderBitmap(context, text, s))
        p.edit().putString(KEY_LAST_TEXT, text).apply()
        return text
    }

    /**
     * Live-engine frame. Countdown mode ticks on exact 100ms wall-clock
     * boundaries (settled flip cards — the tenths digit IS the motion);
     * age mode uses flip animation frames or adaptive sleeps. Returns how
     * long the engine should sleep before the next frame.
     */
    fun liveTick(context: Context, s: WidgetSettings): Long {
        val now = System.currentTimeMillis()
        val text = widgetText(s, now)
        prefs(context).edit().putLong(KEY_LAST_TICK, now).apply()

        if (s.mode == MODE_DOWN) {
            flipCur = text
            flipPrev = text
            if (text != prefs(context).getString(KEY_LAST_TEXT, null)) {
                pushFrame(context, s, text, renderBitmap(context, text, s))
                prefs(context).edit().putString(KEY_LAST_TEXT, text).apply()
            }
            // Exact boundary alignment: no cumulative drift, tenths turn over
            // precisely when the clock does.
            return AgeUtils.countdownFrameDelay(now)
        }

        if (s.style == STYLE_FLIP && text.length == flipPrev.length) {
            if (flipCur == null) {
                flipCur = text
                flipPrev = text
            }
            if (text != flipCur) {
                flipPrev = flipCur!!
                flipCur = text
                flipStart = now
            }
            val progress = FlipClock.flipProgress(now, flipStart)
            val fontPx =
                if (s.mode == MODE_DOWN) countdownFontPx(text) else FlipClock.FONT_PX
            pushFrame(
                context, s, text,
                FlipClock.render(text, flipPrev, progress, s.dotColor, FlipClock.typeface(context), fontPx),
            )
            prefs(context).edit().putString(KEY_LAST_TEXT, text).apply()
            if (progress < 1f) return FlipClock.FRAME_MS
        } else {
            flipCur = text
            flipPrev = text
            if (text != prefs(context).getString(KEY_LAST_TEXT, null)) {
                pushFrame(context, s, text, renderBitmap(context, text, s))
                prefs(context).edit().putString(KEY_LAST_TEXT, text).apply()
            }
        }
        return AgeUtils.msUntilTextChange(s.dobMillis, now, s.decimals)
            .coerceAtLeast(TickerService.LIVE_FLOOR_MS)
    }

    private fun pushFrame(context: Context, s: WidgetSettings, text: String, bmp: Bitmap) {
        val mgr = AppWidgetManager.getInstance(context)
        val density = context.resources.displayMetrics.density
        val pad = (digitPaddingDp(s.digitSize) * density).roundToInt()
        try {
            for ((_, ids) in allWidgetIds(context)) {
                for (id in ids) {
                    mgr.updateAppWidget(
                        id, buildViews(context, id, bmp, text, cardAlpha(s), pad),
                    )
                }
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** One frame now + re-arm alarms. */
    fun updateAll(context: Context) {
        if (settings(context).live) ensureLiveEngine(context)
        renderNow(context)
        schedule(context)
    }

    /** Starts the live engine if it should run but doesn't (best effort). */
    fun ensureLiveEngine(context: Context) {
        if (TickerService.isRunning) return
        try {
            TickerService.start(context)
        } catch (_: Exception) {
            // Background-start denied (Android 12+): minute/watchdog ticks
            // keep the widget fresh until the user opens the app again.
        }
    }

    private fun buildViews(
        context: Context,
        widgetId: Int,
        bmp: Bitmap,
        text: String,
        alpha: Int,
        paddingPx: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_age)
        views.setImageViewBitmap(R.id.dot_image, bmp)
        views.setContentDescription(R.id.dot_image, text)
        // Alpha on the dedicated bg layer: rounded corners survive, unlike
        // setBackgroundColor which would square them off.
        views.setInt(R.id.card_bg, "setImageAlpha", alpha)
        // Digit size: inner padding squeezes the digit area (RemoteViews-safe).
        views.setViewPadding(R.id.dot_wrap, paddingPx, paddingPx, paddingPx, paddingPx)
        val cfg = PendingIntent.getActivity(
            context, widgetId,
            ConfigActivity.intentForWidget(context, widgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, cfg)
        return views
    }

    // ---- scheduling --------------------------------------------------------

    /**
     * Always arms the inexact next-minute alarm — in BOTH modes. In live
     * mode the service owns sub-minute ticks, but if any killer ever stops
     * it, this alarm caps the freeze at ~60s AND attempts a revive via
     * [ensureLiveEngine]. A dead engine therefore degrades to minute
     * granularity instead of freezing for hours.
     */
    fun schedule(context: Context, s: WidgetSettings = settings(context)) {
        if (!hasWidgets(context)) {
            cancel(context)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = (System.currentTimeMillis() / 60_000) * 60_000 + 62_000
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC, trigger, tickIntent(context))
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC, trigger, tickIntent(context))
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(tickIntent(context))
    }

    private fun tickIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AgeWidgetProvider::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ---- battery-optimization exemption ------------------------------------

    /** Gate 1 (AOSP Doze): true when the system may not freeze our ticks. */
    fun isExempted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Whether the stock one-tap allowlist dialog exists on this device.
     * On many OnePlus/Oppo/Realme builds it does NOT resolve — the caller
     * must use [openOptimizationList] instead of dumping the user on a
     * generic app-details page that changes nothing.
     */
    fun hasDirectExemptionDialog(context: Context): Boolean {
        val i = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        return i.resolveActivity(context.packageManager) != null
    }

    /** Stock dialog. Returns false if it couldn't be opened. */
    fun requestExemption(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * System "Battery optimization" list (All apps → Age in Motion →
     * Don't optimize). This screen exists on virtually every OEM skin and
     * is the reliable route when the direct dialog doesn't.
     */
    fun openOptimizationList(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Exception) {
            openAppDetails(context)
            false
        }
    }

    fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    /** Gate 2 (OEM killer): manufacturer-specific steps, since every skin hides them. */
    fun oemSteps(context: Context): String {
        val m = Build.MANUFACTURER.lowercase()
        val app = context.getString(R.string.app_name)
        return when {
            "oneplus" in m || "oppo" in m || "realme" in m ->
                "1. Battery → Battery optimization → “$app” → Don’t optimize\n" +
                    "2. App info → Battery → Allow background usage ON\n" +
                    "3. Recent apps → tap ⋮ on “$app” → Lock"
            "xiaomi" in m || "redmi" in m || "poco" in m ->
                "1. Settings → Battery → “$app” → No restrictions\n" +
                    "2. Security → Autostart → enable “$app”\n" +
                    "3. Recent apps → long-press “$app” → lock it"
            "vivo" in m || "iqoo" in m ->
                "1. Settings → Battery → Background power use → allow “$app”\n" +
                    "2. Settings → Battery → “$app” → Allow background use\n" +
                    "3. Recent apps → lock “$app”"
            "samsung" in m ->
                "1. Settings → Battery → Background usage limits → Never sleeping apps → add “$app”\n" +
                    "2. App info → Battery → Unrestricted"
            "huawei" in m || "honor" in m ->
                "1. Settings → Battery → App launch → “$app” → Manage manually → allow all\n" +
                    "2. Phone Manager → Battery → “$app” → keep running"
            else ->
                "1. Settings → Battery → Battery optimization → “$app” → Don’t optimize\n" +
                    "2. App info → Battery → allow background usage"
        }
    }
}
