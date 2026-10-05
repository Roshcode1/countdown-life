package com.ageinmotion.widget

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.ageinmotion.widget.databinding.ActivityConfigBinding
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings screen, mirroring the web recreation's sections:
 * birth date + time, precision, ghost dots — plus a live dot-matrix preview,
 * the "In motion" stats, real-time mode, and the battery setup card.
 *
 * The battery card shows a HEARTBEAT ("refreshed Xs ago · LIVE" vs
 * "FROZEN · last refresh …") so it is always visible whether the fix
 * worked — no more guessing. On OnePlus/Oppo-style phones the stock
 * exemption dialog doesn't exist; the button then opens the system
 * "Battery optimization" list instead, with per-brand steps below.
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isConfigureFlow = false
    private val picked = Calendar.getInstance()
    private var userPicked = false
    private var uiTick = 0

    // Staged until Save (preview follows them live).
    private var currentStyle: String = AgeWidgetRenderer.STYLE_DOTS
    private var currentColor: Int = AgeWidgetRenderer.DEFAULT_DOT_COLOR
    private var currentMode: String = AgeWidgetRenderer.MODE_AGE

    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewTick = object : Runnable {
        override fun run() {
            uiTick++
            renderPreview()
            renderHeartbeat()
            if (uiTick % 8 == 0) refreshBatteryCard()
            previewHandler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        isConfigureFlow = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (isConfigureFlow) setResult(RESULT_CANCELED)

        val s = AgeWidgetRenderer.settings(this)
        currentMode = s.mode
        if (s.dobMillis != 0L) {
            picked.timeInMillis = s.dobMillis
            userPicked = true
        } else {
            resetPickedToModeDefault()
        }

        binding.precisionSeek.max = 10
        binding.precisionSeek.progress = s.decimals
        binding.opacitySeek.max = 100
        binding.opacitySeek.progress = s.opacity
        binding.digitSeek.progress = s.digitSize
        renderDigitLabel(s.digitSize)
        applyPreviewPadding(s.digitSize)
        syncGhostToggle(s.ghost)
        syncLiveToggle(s.live)
        currentStyle = s.style
        currentColor = s.dotColor
        syncThemeIcon()
        renderPickedDateTime()
        renderPrecisionLabel(s.decimals)
        renderOpacityLabel(s.opacity)
        syncStyleToggle()
        syncColorWheel()
        syncModeToggle()
        syncModeVisibility()
        binding.oemSteps.text = AgeWidgetRenderer.oemSteps(this)

        binding.pickDateButton.setOnClickListener { showDatePicker() }
        binding.dateText.setOnClickListener { showDatePicker() }
        binding.pickTimeButton.setOnClickListener { showTimePicker() }

        binding.precisionSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                renderPrecisionLabel(value)
                renderPreview()
            }
            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = Unit
        })
        binding.ghostToggle.addOnButtonCheckedListener { _, _, _ -> renderPreview() }
        binding.colorWheel.onColorChanged = { color ->
            currentColor = color
            refreshColorHex()
            renderPreview()
        }
        binding.brightnessSeek.max = 100
        binding.brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                binding.colorWheel.brightness = value / 100f
                // Re-emit through the wheel so marker + hex stay in sync.
                binding.colorWheel.onColorChanged?.invoke(binding.colorWheel.selectedColor)
            }
            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = Unit
        })
        binding.themeButton.setOnClickListener { cycleTheme() }
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentMode =
                if (checkedId == R.id.mode_countdown_button) AgeWidgetRenderer.MODE_DOWN
                else AgeWidgetRenderer.MODE_AGE
            // A pick valid in one mode is nonsense in the other — reset it.
            val now = System.currentTimeMillis()
            val wantFuture = currentMode == AgeWidgetRenderer.MODE_DOWN
            val ok = userPicked && (picked.timeInMillis > now) == wantFuture
            if (!ok) resetPickedToModeDefault()
            renderPickedDateTime()
            syncModeVisibility()
            renderPreview()
        }
        binding.styleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentStyle =
                if (checkedId == R.id.flip_style_button) AgeWidgetRenderer.STYLE_FLIP
                else AgeWidgetRenderer.STYLE_DOTS
            renderPreview()
        }
        binding.opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                renderOpacityLabel(value)
                renderPreview()
            }
            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = Unit
        })
        binding.digitSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                renderDigitLabel(value)
                applyPreviewPadding(value)
            }
            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = Unit
        })

        binding.batteryButton.setOnClickListener { runBatteryFix() }
        binding.engineButton.setOnClickListener { restartEngine() }
        binding.saveButton.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        // Sync: the notification's Stop action may have flipped live mode.
        syncLiveToggle(AgeWidgetRenderer.settings(this).live)
        // Self-heal: opening the app (or tapping the widget) is a foreground
        // context, so Android always lets us restart a dead live engine here.
        if (isLiveUi() && !TickerService.isRunning) {
            try {
                TickerService.start(this)
            } catch (_: Exception) {
            }
        }
        uiTick = 0
        refreshBatteryCard()
        renderHeartbeat()
        previewHandler.post(previewTick)
    }

    override fun onPause() {
        previewHandler.removeCallbacks(previewTick)
        super.onPause()
    }

    // ---- pickers -----------------------------------------------------------

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                picked.set(y, m, d)
                userPicked = true
                renderPickedDateTime()
                renderPreview()
            },
            picked.get(Calendar.YEAR),
            picked.get(Calendar.MONTH),
            picked.get(Calendar.DAY_OF_MONTH),
        ).apply {
            val now = System.currentTimeMillis()
            if (currentMode == AgeWidgetRenderer.MODE_DOWN) {
                // Target must be in the future (cap the picker at +100 years).
                datePicker.minDate = now
                datePicker.maxDate = now + 100L * 365L * 86_400_000L
            } else {
                datePicker.minDate = -2208988800000L // 1900-01-01
                datePicker.maxDate = now
            }
            show()
        }
    }

    private fun showTimePicker() {
        TimePickerDialog(
            this,
            { _, h, min ->
                picked.set(Calendar.HOUR_OF_DAY, h)
                picked.set(Calendar.MINUTE, min)
                picked.set(Calendar.SECOND, 0)
                picked.set(Calendar.MILLISECOND, 0)
                userPicked = true
                renderPickedDateTime()
                renderPreview()
            },
            picked.get(Calendar.HOUR_OF_DAY),
            picked.get(Calendar.MINUTE),
            false,
        ).show()
    }

    private fun renderPickedDateTime() {
        val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(picked.timeInMillis))
        val time = String.format(
            Locale.US, "%02d:%02d",
            picked.get(Calendar.HOUR_OF_DAY), picked.get(Calendar.MINUTE),
        )
        binding.dateText.text = "$date · $time"
    }

    private fun renderPrecisionLabel(decimals: Int) {
        binding.precisionLabel.text = getString(R.string.precision_label, decimals)
    }

    private fun renderOpacityLabel(opacity: Int) {
        binding.opacityLabel.text = getString(R.string.opacity_label, opacity)
    }

    private fun renderDigitLabel(size: Int) {
        binding.digitLabel.text = getString(R.string.digit_label, size)
    }

    private fun applyPreviewPadding(size: Int) {
        val px = (AgeWidgetRenderer.digitPaddingDp(size) * resources.displayMetrics.density).roundToInt()
        binding.previewWrap.setPadding(px, px, px, px)
    }

    /** Demo anchor per mode: a 1997 birthday, or 30 days out for countdown. */
    private fun resetPickedToModeDefault() {
        if (currentMode == AgeWidgetRenderer.MODE_DOWN) {
            picked.timeInMillis = System.currentTimeMillis() + 30L * 86_400_000L
        } else {
            picked.set(1997, Calendar.AUGUST, 15, 9, 30, 0)
        }
        picked.set(Calendar.SECOND, 0)
        picked.set(Calendar.MILLISECOND, 0)
        userPicked = false
    }

    private fun syncModeToggle() {
        binding.modeToggle.check(
            if (currentMode == AgeWidgetRenderer.MODE_DOWN) R.id.mode_countdown_button
            else R.id.mode_age_button,
        )
    }

    private fun syncModeVisibility() {
        // Decimals don't exist in countdown mode (fixed tenths) — hide them.
        val vis = if (currentMode == AgeWidgetRenderer.MODE_DOWN) View.GONE else View.VISIBLE
        binding.precisionLabel.visibility = vis
        binding.precisionSeek.visibility = vis
    }

    private fun syncStyleToggle() {
        binding.styleToggle.check(
            if (currentStyle == AgeWidgetRenderer.STYLE_FLIP) R.id.flip_style_button
            else R.id.dots_style_button,
        )
    }

    private fun syncThemeIcon() {
        val mode = AgeWidgetRenderer.prefs(this).getString(KEY_THEME, THEME_SYSTEM)
        binding.themeButton.setImageResource(
            when (mode) {
                THEME_DARK -> R.drawable.ic_section_theme
                THEME_LIGHT -> R.drawable.ic_theme_sun
                else -> R.drawable.ic_theme_auto
            },
        )
    }

    private fun cycleTheme() {
        val cur = AgeWidgetRenderer.prefs(this).getString(KEY_THEME, THEME_SYSTEM)
        val next = when (cur) {
            THEME_SYSTEM -> THEME_DARK
            THEME_DARK -> THEME_LIGHT
            else -> THEME_SYSTEM
        }
        AgeWidgetRenderer.prefs(this).edit().putString(KEY_THEME, next).apply()
        applySavedTheme()
        syncThemeIcon()
        Toast.makeText(
            this,
            when (next) {
                THEME_DARK -> "Dark theme"
                THEME_LIGHT -> "Light theme"
                else -> "System theme"
            },
            Toast.LENGTH_SHORT,
        ).show()
        recreate()
    }

    private fun applySavedTheme() {
        val mode = getSharedPreferences(AgeWidgetRenderer.PREFS, MODE_PRIVATE)
            .getString(KEY_THEME, THEME_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    private fun syncColorWheel() {
        binding.colorWheel.setColor(currentColor)
        val brightest = maxOf(
            android.graphics.Color.red(currentColor),
            android.graphics.Color.green(currentColor),
            android.graphics.Color.blue(currentColor),
        )
        binding.brightnessSeek.progress = (brightest * 100 / 255).coerceIn(0, 100)
        refreshColorHex()
    }

    private fun refreshColorHex() {
        binding.colorHex.text = String.format("#%06X", 0xFFFFFF and currentColor)
    }

    // ---- live preview --------------------------------------------------------

    private fun renderPreview() {
        val decimals = binding.precisionSeek.progress
        val ghost = isGhostUi()
        val alpha = (binding.opacitySeek.progress * 255 / 100).coerceIn(0, 255)
        binding.previewCardBg.imageAlpha = alpha
        val birth = picked.timeInMillis
        val now = System.currentTimeMillis()
        val snap = AgeWidgetRenderer.WidgetSettings(
            dobMillis = birth,
            decimals = decimals,
            ghost = ghost,
            live = false,
            opacity = binding.opacitySeek.progress,
            style = currentStyle,
            dotColor = currentColor,
            mode = currentMode,
            digitSize = binding.digitSeek.progress,
        )
        val text =
            if (currentMode == AgeWidgetRenderer.MODE_AGE && birth >= now) {
                AgeWidgetRenderer.PLACEHOLDER
            } else {
                AgeWidgetRenderer.widgetText(snap, now)
            }
        val old = binding.previewImage.getTag(R.id.preview_image) as? android.graphics.Bitmap
        val bmp = AgeWidgetRenderer.renderBitmap(this, text, snap)
        binding.previewImage.setImageBitmap(bmp)
        binding.previewImage.setTag(R.id.preview_image, bmp)
        old?.takeIf { !it.isRecycled }?.recycle()
    }

    // ---- heartbeat: proof the engine is alive --------------------------------

    private fun renderHeartbeat() {
        val p = AgeWidgetRenderer.prefs(this)
        val last = p.getLong(AgeWidgetRenderer.KEY_LAST_TICK, 0L)
        val now = System.currentTimeMillis()
        val live = isLiveUi() || AgeWidgetRenderer.settings(this).live

        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        val (text, colorRes) = when {
            !interactive -> getString(R.string.heartbeat_paused) to android.R.color.holo_blue_light
            last == 0L -> getString(R.string.heartbeat_never) to android.R.color.holo_orange_light
            now - last <= (if (live) 10_000L else 5 * 60_000L) ->
                getString(R.string.heartbeat_live, ((now - last) / 1000).toInt()) to
                    android.R.color.holo_green_light
            else -> getString(R.string.heartbeat_frozen, describeAge(now - last)) to
                android.R.color.holo_red_light
        }
        binding.heartbeatText.text = text
        binding.heartbeatText.setTextColor(getColor(colorRes))

        // Engine state + restart control: visible proof + one-tap revive.
        val engineOn = TickerService.isRunning
        binding.engineState.text = getString(
            if (engineOn) R.string.engine_on else R.string.engine_off,
        )
        binding.engineState.setTextColor(
            getColor(if (engineOn) android.R.color.holo_green_light else android.R.color.holo_orange_light),
        )
        binding.engineButton.visibility =
            if (live && !engineOn) View.VISIBLE else View.GONE
    }

    private fun restartEngine() {
        TickerService.stop(this)
        try {
            TickerService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "System blocked restart: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        AgeWidgetRenderer.updateAll(this)
        Toast.makeText(this, "Live engine restarted — watch the heartbeat", Toast.LENGTH_SHORT).show()
    }

    private fun isLiveUi(): Boolean =
        binding.liveToggle.checkedButtonId == R.id.live_yes_button

    private fun isGhostUi(): Boolean =
        binding.ghostToggle.checkedButtonId == R.id.ghost_yes_button

    private fun syncGhostToggle(ghost: Boolean) {
        binding.ghostToggle.check(
            if (ghost) R.id.ghost_yes_button else R.id.ghost_no_button,
        )
    }

    private fun syncLiveToggle(live: Boolean) {
        binding.liveToggle.check(
            if (live) R.id.live_yes_button else R.id.live_no_button,
        )
    }

    private fun describeAge(ageMs: Long): String {
        val s = ageMs / 1000
        return when {
            s < 90 -> "$s s"
            s < 5400 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }

    // ---- battery card --------------------------------------------------------

    private fun refreshBatteryCard() {
        val exempted = AgeWidgetRenderer.isExempted(this)
        binding.batteryStatus.text = getString(
            if (exempted) R.string.battery_ok else R.string.battery_restricted,
        )
        binding.batteryStatus.setTextColor(
            getColor(if (exempted) android.R.color.holo_green_light else android.R.color.holo_orange_light),
        )
        binding.batteryButton.text = getString(
            if (exempted) R.string.battery_open_settings else R.string.battery_fix,
        )
        binding.batteryHint.visibility = if (exempted) View.GONE else View.VISIBLE
        binding.oemSteps.visibility = if (exempted) View.GONE else View.VISIBLE
    }

    /**
     * The fix flow, adapted to what THIS phone can actually do:
     *  - stock dialog exists → open it ("Allow" = done);
     *  - OnePlus/Oppo-style (no dialog) → open the system optimization LIST
     *    with exact instructions, never a dead-end details page.
     */
    private fun runBatteryFix() {
        if (AgeWidgetRenderer.isExempted(this)) {
            AgeWidgetRenderer.openAppDetails(this)
            return
        }
        if (AgeWidgetRenderer.hasDirectExemptionDialog(this)) {
            AgeWidgetRenderer.requestExemption(this)
            Toast.makeText(this, "Tap “Allow” — then watch the status above turn green", Toast.LENGTH_LONG).show()
        } else {
            AgeWidgetRenderer.openOptimizationList(this)
            Toast.makeText(
                this,
                "Tap “Age in Motion” in the list → “Don’t optimize”",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ---- save ---------------------------------------------------------------

    private fun save() {
        if (!userPicked) {
            Toast.makeText(
                this,
                if (currentMode == AgeWidgetRenderer.MODE_DOWN) "Pick a target date first"
                else "Pick your birth date first",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val countdown = currentMode == AgeWidgetRenderer.MODE_DOWN
        if (countdown && picked.timeInMillis <= System.currentTimeMillis()) {
            Toast.makeText(this, "Target must be in the future", Toast.LENGTH_SHORT).show()
            return
        }
        if (!countdown && picked.timeInMillis >= System.currentTimeMillis()) {
            Toast.makeText(this, "Birthday must be in the past", Toast.LENGTH_SHORT).show()
            return
        }
        val live = isLiveUi()
        AgeWidgetRenderer.prefs(this).edit()
            .putLong(AgeWidgetRenderer.KEY_DOB, picked.timeInMillis)
            .putString(AgeWidgetRenderer.KEY_MODE, currentMode)
            .putInt(AgeWidgetRenderer.KEY_DECIMALS, binding.precisionSeek.progress)
            .putInt(AgeWidgetRenderer.KEY_OPACITY, binding.opacitySeek.progress)
            .putInt(AgeWidgetRenderer.KEY_DIGIT_SIZE, binding.digitSeek.progress)
            .putString(AgeWidgetRenderer.KEY_STYLE, currentStyle)
            .putInt(AgeWidgetRenderer.KEY_DOT_COLOR, currentColor)
            .putBoolean(AgeWidgetRenderer.KEY_GHOST, isGhostUi())
            .putBoolean(AgeWidgetRenderer.KEY_LIVE, live)
            .apply()

        if (live) {
            try {
                TickerService.start(this)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Live engine blocked by system — minute mode for now",
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            TickerService.stop(this)
        }
        AgeWidgetRenderer.updateAll(this)

        if (!AgeWidgetRenderer.isExempted(this)) {
            runBatteryFix()
            Toast.makeText(
                this,
                "Saved! Finish the battery step so it never freezes",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(this, "Saved — widget updated", Toast.LENGTH_SHORT).show()
        }

        if (isConfigureFlow) {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            )
        }
        finish()
    }

    companion object {
        const val KEY_THEME = "app_theme"
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        fun intentForWidget(context: Context, widgetId: Int): Intent =
            Intent(context, ConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("ageinmotion://widget/$widgetId")
            }
    }
}
