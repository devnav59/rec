package com.devnav.rec

import android.content.Intent

/** How the moving anchor is located on the captured screen. */
enum class MarkerMode {
    TEXT,
    IMAGE
}

/** Position of the value relative to the anchor's current bounds. */
enum class RelativePosition {
    RIGHT,
    LEFT,
    BELOW,
    ABOVE,
    NEAREST
}

data class MonitorConfig(
    val markerMode: MarkerMode,
    val markerText: String,
    val templateUri: String?,
    val relativePosition: RelativePosition,
    val scanIntervalMs: Long,
    val announceInitialValue: Boolean
)

/** Intent contract shared by the activity and the foreground service. */
object MonitorContract {
    const val ACTION_START = "com.devnav.rec.action.START_MONITORING"
    const val ACTION_STOP = "com.devnav.rec.action.STOP_MONITORING"
    const val ACTION_STATUS = "com.devnav.rec.action.MONITOR_STATUS"

    const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
    const val EXTRA_PROJECTION_DATA = "projection_data"
    const val EXTRA_MARKER_MODE = "marker_mode"
    const val EXTRA_MARKER_TEXT = "marker_text"
    const val EXTRA_TEMPLATE_URI = "template_uri"
    const val EXTRA_RELATIVE_POSITION = "relative_position"
    const val EXTRA_SCAN_INTERVAL = "scan_interval"
    const val EXTRA_ANNOUNCE_INITIAL = "announce_initial"
    const val EXTRA_STATUS_TEXT = "status_text"
    const val EXTRA_RUNNING = "running"

    fun Intent.putMonitorConfig(config: MonitorConfig): Intent = apply {
        putExtra(EXTRA_MARKER_MODE, config.markerMode.name)
        putExtra(EXTRA_MARKER_TEXT, config.markerText)
        putExtra(EXTRA_TEMPLATE_URI, config.templateUri)
        putExtra(EXTRA_RELATIVE_POSITION, config.relativePosition.name)
        putExtra(EXTRA_SCAN_INTERVAL, config.scanIntervalMs)
        putExtra(EXTRA_ANNOUNCE_INITIAL, config.announceInitialValue)
    }

    fun configFrom(intent: Intent): MonitorConfig? {
        val markerMode = enumOrNull<MarkerMode>(intent.getStringExtra(EXTRA_MARKER_MODE)) ?: return null
        val position = enumOrNull<RelativePosition>(intent.getStringExtra(EXTRA_RELATIVE_POSITION))
            ?: RelativePosition.RIGHT
        val interval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, 900L).coerceIn(500L, 5_000L)
        val markerText = intent.getStringExtra(EXTRA_MARKER_TEXT).orEmpty().trim()
        val templateUri = intent.getStringExtra(EXTRA_TEMPLATE_URI)

        if (markerMode == MarkerMode.TEXT && markerText.isEmpty()) return null
        if (markerMode == MarkerMode.IMAGE && templateUri.isNullOrBlank()) return null

        return MonitorConfig(
            markerMode = markerMode,
            markerText = markerText,
            templateUri = templateUri,
            relativePosition = position,
            scanIntervalMs = interval,
            announceInitialValue = intent.getBooleanExtra(EXTRA_ANNOUNCE_INITIAL, false)
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
        value?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }
}
