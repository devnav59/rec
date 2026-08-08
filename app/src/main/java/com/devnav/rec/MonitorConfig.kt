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

/** Holds a detected value for a specific marker. */
data class MarkerDetection(
    val markerIndex: Int,
    val markerText: String,
    val value: String,
    val rawValue: String
)

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

    private object MonitorConfigSerializer {
        // Simple JSON serialization without external dependencies
        fun toJson(config: MonitorConfig): String {
            val markersJson = config.markers.joinToString(",") { marker ->
                """{"text":"${escapeJson(marker.markerText)}","mode":"${marker.markerMode.name}","pos":"${marker.relativePosition.name}","uri":"${escapeJson(marker.templateUri ?: "")}"}"""
            }
            return """{"markers":[$markersJson],"interval":${config.scanIntervalMs},"announce":${if (config.announceInitialValue) 1 else 0}}"""
        }

        fun fromJson(json: String): MonitorConfig? {
            try {
                val markers = parseMarkerArray(json) ?: return null
                val interval = extractJsonLong(json, "interval", 900L).coerceIn(200L, 5_000L)
                val announce = extractJsonInt(json, "announce", 0) == 1
                return MonitorConfig(
                    markers = markers,
                    scanIntervalMs = interval,
                    announceInitialValue = announce
                )
            } catch (e: Exception) {
                return null
            }
        }

        private fun parseMarkerArray(json: String): List<MarkerConfig>? {
            val startMarker = json.indexOf("\"markers\":[")
            if (startMarker < 0) return null
            val arrayStart = json.indexOf('[', startMarker)
            val arrayEnd = json.indexOf(']', arrayStart)
            if (arrayStart < 0 || arrayEnd < 0) return null

            val arrayContent = json.substring(arrayStart + 1, arrayEnd)
            if (arrayContent.trim().isEmpty()) return emptyList()

            return arrayContent.split("},").map { it.trim() + "}" }.map { parseMarker(it) }
        }

        private fun parseMarker(json: String): MarkerConfig {
            val text = extractJsonString(json, "text", "")
            val mode = enumValues<MarkerMode>().firstOrNull { it.name == extractJsonString(json, "mode", "TEXT") } ?: MarkerMode.TEXT
            val pos = enumValues<RelativePosition>().firstOrNull { it.name == extractJsonString(json, "pos", "RIGHT") } ?: RelativePosition.RIGHT
            val uri = extractJsonString(json, "uri", null)
            return MarkerConfig(
                markerText = text,
                markerMode = mode,
                templateUri = uri,
                relativePosition = pos
            )
        }

        private fun extractJsonString(json: String, key: String, default: String?): String? {
            val pattern = "\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
            return pattern.find(json)?.groupValues?.get(1)?.replace("\\\\\"", "\"") ?: default
        }

        private fun extractJsonString(json: String, key: String, default: String): String {
            return extractJsonString(json, key, default) ?: default
        }

        private fun extractJsonLong(json: String, key: String, default: Long): Long {
            val pattern = "\"$key\":(\\d+)".toRegex()
            return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: default
        }

        private fun extractJsonInt(json: String, key: String, default: Int): Int {
            val pattern = "\"$key\":(\\d+)".toRegex()
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: default
        }

        private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
