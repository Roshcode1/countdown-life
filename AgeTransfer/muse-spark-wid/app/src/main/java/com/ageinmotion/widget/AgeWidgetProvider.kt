package com.ageinmotion.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * Shared behavior for both widget sizes. All rendering/scheduling lives in
 * [AgeWidgetRenderer] so square (2x2) and wide (4x2) always stay in sync.
 * Live mode is owned by [TickerService]; alarms stay on the per-minute
 * cadence in both modes so a killed engine degrades gracefully and revives.
 */
abstract class BaseAgeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AgeWidgetRenderer.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AgeWidgetRenderer.ACTION_TICK -> AgeWidgetRenderer.updateAll(context)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_LOCALE_CHANGED,
            -> AgeWidgetRenderer.updateAll(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (AgeWidgetRenderer.settings(context).live) {
                    AgeWidgetRenderer.ensureLiveEngine(context)
                }
                AgeWidgetRenderer.updateAll(context)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Re-arm while any widget (either size) remains.
        AgeWidgetRenderer.schedule(context)
    }

    override fun onDisabled(context: Context) {
        if (!AgeWidgetRenderer.hasWidgets(context)) {
            TickerService.stop(context)
            AgeWidgetRenderer.cancel(context)
        }
    }
}

/** Square 2x2 widget — the reference design's default size. */
class AgeWidgetProvider : BaseAgeWidgetProvider()

/** Wide 4x2 widget — the reference design's wide size. */
class AgeWidgetWideProvider : BaseAgeWidgetProvider()
