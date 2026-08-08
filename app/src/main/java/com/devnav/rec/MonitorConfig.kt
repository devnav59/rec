package com.devnav.rec

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

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

/** A single marker with its detection configuration. */
data class MarkerConfig(
    val markerText: String,
    val markerMode: MarkerMode,
    val templateUri: String?,
    val relativePosition: RelativePosition
)

/** Main monitoring configuration supporting multiple markers. */
data class MonitorConfig(
    val markers: List<MarkerConfig>,
    val scanIntervalMs: Long,
    val announceInitialValue: Boolean
)

/** Serializes monitor settings for both SharedPreferences and the service Intent. */
object MonitorConfigSerializer {
    fun toJson(config: MonitorConfig): String {
        val markers = JSONArray()
        config.markers.forEach { marker ->
            markers.put(
                JSONObject().apply {
                    put(KEY_TEXT, marker.markerText)
                    put(KEY_MODE, marker.markerMode.name)
                    put(KEY_POSITION, marker.relativePosition.name)
                    marker.templateUri?.let { put(KEY_URI, it) }
                }
            )
        }

        return JSONObject().apply {
            put(KEY_MARKERS, markers)
            put(KEY_INTERVAL, config.scanIntervalMs)
            put(KEY_ANNOUNCE, config.announceInitialValue)
        }.toString()
    }

    fun fromJson(json: String): MonitorConfig? = runCatching {
        val root = JSONObject(json)
        val markerArray = root.optJSONArray(KEY_MARKERS) ?: return null
        val markers = ArrayList<MarkerConfig>(markerArray.length())

        for (index in 0 until markerArray.length()) {
            val item = markerArray.optJSONObject(index) ?: return null
            val markerMode = enumValueOrDefault(
                item.optString(KEY_MODE),
                MarkerMode.TEXT
            )
            val markerText = item.optString(KEY_TEXT).trim()
            val templateUri = item.optString(KEY_URI).trim().takeIf { it.isNotEmpty() }
            val relativePosition = enumValueOrDefault(
                item.optString(KEY_POSITION),
                RelativePosition.RIGHT
            )

            if (markerMode == MarkerMode.TEXT && markerText.isEmpty()) return null
            if (markerMode == MarkerMode.IMAGE && templateUri == null) return null

            markers += MarkerConfig(
                markerText = markerText,
                markerMode = markerMode,
                templateUri = templateUri,
                relativePosition = relativePosition
            )
        }

        if (markers.isEmpty()) return null

        MonitorConfig(
            markers = markers,
            scanIntervalMs = root.optLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS)
                .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            announceInitialValue = root.optBoolean(KEY_ANNOUNCE, false)
        )
    }.getOrNull()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private const val KEY_MARKERS = "markers"
    private const val KEY_TEXT = "text"
    private const val KEY_MODE = "mode"
    private const val KEY_POSITION = "pos"
    private const val KEY_URI = "uri"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_ANNOUNCE = "announce"
    private const val DEFAULT_INTERVAL_MS = 900L
    private const val MIN_INTERVAL_MS = 200L
    private const val MAX_INTERVAL_MS = 5_000L
}

/** Intent contract shared by the activity and the foreground service. */
object MonitorContract {
    const val ACTION_START = "com.devnav.rec.action.START_MONITORING"
    const val ACTION_STOP = "com.devnav.rec.action.STOP_MONITORING"
    const val ACTION_STATUS = "com.devnav.rec.action.MONITOR_STATUS"

    const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
    const val EXTRA_PROJECTION_DATA = "projection_data"
    const val EXTRA_CONFIG_JSON = "config_json"
    const val EXTRA_STATUS_TEXT = "status_text"
    const val EXTRA_RUNNING = "running"

    fun Intent.putMonitorConfig(config: MonitorConfig): Intent = apply {
        putExtra(EXTRA_CONFIG_JSON, MonitorConfigSerializer.toJson(config))
    }

    fun configFrom(intent: Intent): MonitorConfig? {
        val json = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return null
        return MonitorConfigSerializer.fromJson(json)
    }
}
