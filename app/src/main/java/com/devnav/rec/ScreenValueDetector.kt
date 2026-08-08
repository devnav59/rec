package com.devnav.rec

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A screen-space rectangle kept Android-free so matching logic can be unit tested. */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(right >= left) { "right must be >= left" }
        require(bottom >= top) { "bottom must be >= top" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
}

data class OcrToken(
    val text: String,
    val bounds: Bounds
)

data class NumberDetection(
    /** ASCII, canonical form used for comparing readings, e.g. ۱۲٬۳۴۵ -> 12345. */
    val value: String,
    val rawValue: String,
    val anchorBounds: Bounds,
    val valueBounds: Bounds
)

/** Result of detecting a number for a specific marker. */
data class MarkerResult(
    val markerText: String,
    val value: String,
    val rawValue: String,
    val found: Boolean
)

/**
 * Extracts decimal-looking values and converts Persian/Arabic digits to a stable representation.
 * It deliberately does not parse to Double, so large counters keep all of their digits.
 */
object NumberParser {
    private val numberPattern = Regex("""[+-]?\d(?:[\\d,.]*\d)?""")

    fun extract(text: String): List<NumberMatch> {
        val normalized = normalizeCharacters(text)
        return numberPattern.findAll(normalized).map { match ->
            NumberMatch(raw = match.value, canonical = match.value.replace(",", ""))
        }.toList()
    }

    fun canonicalize(text: String): String? = extract(text).firstOrNull()?.canonical

    fun normalizeCharacters(text: String): String = buildString(text.length) {
        text.forEach { char ->
            append(
                when (char) {
                    '۰' -> '0'
                    '۱' -> '1'
                    '۲' -> '2'
                    '۳' -> '3'
                    '۴' -> '4'
                    '۵' -> '5'
                    '۶' -> '6'
                    '۷' -> '7'
                    '۸' -> '8'
                    '۹' -> '9'
                    '٠' -> '0'
                    '١' -> '1'
                    '٢' -> '2'
                    '٣' -> '3'
                    '٤' -> '4'
                    '٥' -> '5'
                    '٦' -> '6'
                    '٧' -> '7'
                    '٨' -> '8'
                    '٩' -> '9'
                    '٫' -> '.'
                    '٬', '،' -> ','
                    '−', '–', '—' -> '-'
                    else -> char
                }
            )
        }
    }

    /**
     * Normalize the value to a stable English format with dot as decimal separator.
     * This is the canonical form used for display and comparison.
     */
    fun toEnglishFormat(value: String): String {
        val normalized = normalizeCharacters(value)
        // Remove thousands separators (commas)
        val withoutThousands = normalized.replace(",", "")
        return withoutThousands
    }

    fun toPersianDigits(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(if (char in '0'..'9') ('۰'.code + (char - '0')).toChar() else char)
        }
    }
}

data class NumberMatch(val raw: String, val canonical: String)

/** Links OCR tokens to a moving marker. For text mode, marker bounds are found from OCR each
 * frame. For image mode, [findNearAnchor] accepts bounds supplied by TemplateMatcher. */
object ScreenValueDetector {
    fun hasTextAnchor(tokens: List<OcrToken>, markerText: String): Boolean {
        val markerKey = compact(markerText)
        return markerKey.isNotEmpty() && tokens.any { compact(it.text).contains(markerKey) }
    }

    fun findByText(
        tokens: List<OcrToken>,
        markerText: String,
        position: RelativePosition
    ): NumberDetection? {
        val markerKey = compact(markerText)
        if (markerKey.isEmpty()) return null

        val anchors = tokens.filter { compact(it.text).contains(markerKey) }
        return findForAnchors(tokens, anchors, position, markerKey)
    }

    fun findNearAnchor(
        tokens: List<OcrToken>,
        anchorBounds: Bounds,
        position: RelativePosition
    ): NumberDetection? = findForAnchors(
        tokens = tokens,
        anchors = listOf(OcrToken(text = "", bounds = anchorBounds)),
        position = position,
        exactMarkerKey = null
    )

    /**
     * Detect values for multiple markers in a single frame pass.
     * Each marker is searched independently.
     */
    fun findMultipleMarkers(
        tokens: List<OcrToken>,
        markers: List<MarkerConfig>,
        imageAnchor: Bounds?
    ): List<MarkerResult> {
        return markers.mapIndexed { index, marker ->
            detectForMarker(tokens, index, marker, imageAnchor)
        }
    }

    private fun detectForMarker(
        tokens: List<OcrToken>,
        markerIndex: Int,
        marker: MarkerConfig,
        imageAnchor: Bounds?
    ): MarkerResult {
        val detection = when (marker.markerMode) {
            MarkerMode.TEXT -> {
                val markerKey = compact(marker.markerText)
                if (markerKey.isEmpty()) {
                    NumberDetection("0", "0", Bounds(0, 0, 1, 1), Bounds(0, 0, 1, 1))
                } else {
                    val anchors = tokens.filter { compact(it.text).contains(markerKey) }
                    if (anchors.isEmpty()) {
                        NumberDetection("", "", Bounds(0, 0, 1, 1), Bounds(0, 0, 1, 1))
                    } else {
                        findForAnchors(tokens, anchors, marker.relativePosition, markerKey)
                    }
                }
            }
            MarkerMode.IMAGE -> {
                imageAnchor?.let { anchor ->
                    findForAnchors(
                        tokens = tokens,
                        anchors = listOf(OcrToken(text = "", bounds = anchor)),
                        position = marker.relativePosition,
                        exactMarkerKey = null
                    )
                } ?: NumberDetection("", "", Bounds(0, 0, 1, 1), Bounds(0, 0, 1, 1))
            }
        }

        val value = detection.value
        return if (value.isEmpty()) {
            MarkerResult(
                markerText = marker.markerText,
                value = "",
                rawValue = "",
                found = false
            )
        } else {
            MarkerResult(
                markerText = marker.markerText,
                value = NumberParser.toEnglishFormat(value),
                rawValue = detection.rawValue,
                found = true
            )
        }
    }

    private fun findForAnchors(
        tokens: List<OcrToken>,
        anchors: List<OcrToken>,
        position: RelativePosition,
        exactMarkerKey: String?
    ): NumberDetection? {
        var best: ScoredDetection? = null

        for (anchor in anchors) {
            for (token in tokens) {
                val isAnchorToken = token == anchor
                // A line such as "HP 100" is useful, while an anchor token exactly equal to
                // "HP" has no value of its own and should not compete with nearby values.
                val directValueAllowed = isAnchorToken &&
                    exactMarkerKey != null && compact(token.text) != exactMarkerKey
                if (isAnchorToken && !directValueAllowed) continue

                val score = if (directValueAllowed) {
                    -1.0
                } else {
                    positionScore(anchor.bounds, token.bounds, position) ?: continue
                }

                for (match in NumberParser.extract(token.text)) {
                    val candidate = ScoredDetection(
                        score = score,
                        detection = NumberDetection(
                            value = match.canonical,
                            rawValue = match.raw,
                            anchorBounds = anchor.bounds,
                            valueBounds = token.bounds
                        )
                    )
                    if (best == null || candidate.score < best.score) best = candidate
                }
            }
        }
        return best?.detection
    }

    private fun positionScore(
        anchor: Bounds,
        candidate: Bounds,
        position: RelativePosition
    ): Double? {
        val horizontalTolerance = max(6.0, anchor.width * 0.25)
        val verticalTolerance = max(6.0, anchor.height * 0.25)

        return when (position) {
            RelativePosition.RIGHT -> {
                val gap = candidate.left - anchor.right
                if (gap < -horizontalTolerance) null
                else max(0.0, gap.toDouble()) + abs(candidate.centerY - anchor.centerY) * 1.2
            }

            RelativePosition.LEFT -> {
                val gap = anchor.left - candidate.right
                if (gap < -horizontalTolerance) null
                else max(0.0, gap.toDouble()) + abs(candidate.centerY - anchor.centerY) * 1.2
            }

            RelativePosition.BELOW -> {
                val gap = candidate.top - anchor.bottom
                if (gap < -verticalTolerance) null
                else max(0.0, gap.toDouble()) + abs(candidate.centerX - anchor.centerX) * 1.2
            }

            RelativePosition.ABOVE -> {
                val gap = anchor.top - candidate.bottom
                if (gap < -verticalTolerance) null
                else max(0.0, gap.toDouble()) + abs(candidate.centerX - anchor.centerX) * 1.2
            }

            RelativePosition.NEAREST -> hypot(
                candidate.centerX - anchor.centerX,
                candidate.centerY - anchor.centerY
            )
        }
    }

    private fun compact(text: String): String = buildString(text.length) {
        NumberParser.normalizeCharacters(text).lowercase(Locale.ROOT).forEach { char ->
            when (char) {
                ' ', '\n', '\t', '\r', '\u200c', '\u200d' -> Unit
                'ي' -> append('ی')
                'ك' -> append('ک')
                else -> append(char)
            }
        }
    }

    private data class ScoredDetection(
        val score: Double,
        val detection: NumberDetection
    )
}

data class ValueChange(
    val previous: String?,
    val current: String,
    val isInitial: Boolean
)

/** Manages value change detection with consecutive read confirmation. */
class ValueChangeGate(private val requiredConsecutiveReads: Int = 2) {
    init {
        require(requiredConsecutiveReads > 0)
    }

    private var accepted: String? = null
    private var pending: String? = null
    private var pendingCount = 0

    fun offer(candidate: String): ValueChange? {
        if (candidate == accepted) {
            pending = null
            pendingCount = 0
            return null
        }

        if (candidate == pending) {
            pendingCount += 1
        } else {
            pending = candidate
            pendingCount = 1
        }

        if (pendingCount < requiredConsecutiveReads) return null

        val previous = accepted
        accepted = candidate
        pending = null
        pendingCount = 0
        return ValueChange(previous = previous, current = candidate, isInitial = previous == null)
    }

    fun miss() {
        pending = null
        pendingCount = 0
    }

    fun reset() {
        accepted = null
        pending = null
        pendingCount = 0
    }
}
