package com.devnav.rec

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Small, dependency-free template matcher used for graphic anchors. It compares a sampled,
 * brightness-normalized crop against the captured frame. The template is intentionally expected
 * to be a tight crop at the same on-screen scale; this keeps matching private and lightweight.
 */
class TemplateMatcher private constructor(template: Bitmap) {
    private val templateWidth = template.width
    private val templateHeight = template.height
    private val sampleXs: IntArray
    private val sampleYs: IntArray
    private val centeredTemplate: IntArray
    private val templateContrast: Float
    private val allowedScore: Float

    // Reused between frames to avoid allocating a full-screen pixel array every scan.
    private var sourcePixels = IntArray(0)

    val isUsable: Boolean
        get() = templateWidth >= MIN_TEMPLATE_SIDE &&
            templateHeight >= MIN_TEMPLATE_SIDE &&
            templateContrast >= MIN_CONTRAST

    init {
        val columns = min(MAX_SAMPLES_PER_AXIS, max(MIN_SAMPLES_PER_AXIS, templateWidth / 4))
        val rows = min(MAX_SAMPLES_PER_AXIS, max(MIN_SAMPLES_PER_AXIS, templateHeight / 4))
        sampleXs = IntArray(columns) { index ->
            (((index + 0.5) * templateWidth) / columns).toInt().coerceIn(0, templateWidth - 1)
        }
        sampleYs = IntArray(rows) { index ->
            (((index + 0.5) * templateHeight) / rows).toInt().coerceIn(0, templateHeight - 1)
        }

        val pixels = IntArray(templateWidth * templateHeight)
        template.getPixels(pixels, 0, templateWidth, 0, 0, templateWidth, templateHeight)
        val samples = IntArray(sampleXs.size * sampleYs.size)
        var sampleIndex = 0
        var mean = 0
        for (y in sampleYs) {
            for (x in sampleXs) {
                val gray = grayscale(pixels[y * templateWidth + x])
                samples[sampleIndex++] = gray
                mean += gray
            }
        }
        mean /= samples.size

        centeredTemplate = IntArray(samples.size)
        var contrastSum = 0
        samples.forEachIndexed { index, gray ->
            val centered = gray - mean
            centeredTemplate[index] = centered
            contrastSum += abs(centered)
        }
        templateContrast = contrastSum.toFloat() / samples.size
        // The normalisation above makes this tolerate modest brightness changes while rejecting
        // unrelated screen areas. High-contrast symbols get a slightly wider tolerance.
        allowedScore = (12f + templateContrast * 0.65f).coerceIn(18f, 54f)
    }

    fun find(source: Bitmap): Bounds? {
        if (!isUsable || source.width < templateWidth || source.height < templateHeight) return null

        val requiredPixels = source.width * source.height
        if (sourcePixels.size != requiredPixels) sourcePixels = IntArray(requiredPixels)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)

        val maxX = source.width - templateWidth
        val maxY = source.height - templateHeight
        val coarseStep = max(4, min(templateWidth, templateHeight) / 6)
        var bestX = 0
        var bestY = 0
        var bestScore = Float.MAX_VALUE

        var y = 0
        while (y <= maxY) {
            var x = 0
            while (x <= maxX) {
                val score = scoreAt(x, y, source.width)
                if (score < bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += coarseStep
            }
            y += coarseStep
        }
        // Ensure the bottom/right edges are candidates even when not divisible by coarseStep.
        evaluateCandidate(maxX, maxY, source.width) { score ->
            if (score < bestScore) {
                bestScore = score
                bestX = maxX
                bestY = maxY
            }
        }

        // Refine the best coarse location at pixel precision.
        val startX = (bestX - coarseStep).coerceAtLeast(0)
        val endX = (bestX + coarseStep).coerceAtMost(maxX)
        val startY = (bestY - coarseStep).coerceAtLeast(0)
        val endY = (bestY + coarseStep).coerceAtMost(maxY)
        for (refineY in startY..endY) {
            for (refineX in startX..endX) {
                val score = scoreAt(refineX, refineY, source.width)
                if (score < bestScore) {
                    bestScore = score
                    bestX = refineX
                    bestY = refineY
                }
            }
        }

        return if (bestScore <= allowedScore) {
            Bounds(bestX, bestY, bestX + templateWidth, bestY + templateHeight)
        } else {
            null
        }
    }

    private inline fun evaluateCandidate(
        x: Int,
        y: Int,
        sourceWidth: Int,
        onScore: (Float) -> Unit
    ) {
        onScore(scoreAt(x, y, sourceWidth))
    }

    private fun scoreAt(originX: Int, originY: Int, sourceWidth: Int): Float {
        var sourceMean = 0
        var index = 0
        for (y in sampleYs) {
            val row = (originY + y) * sourceWidth
            for (x in sampleXs) {
                sourceMean += grayscale(sourcePixels[row + originX + x])
                index += 1
            }
        }
        sourceMean /= index

        var difference = 0
        index = 0
        for (y in sampleYs) {
            val row = (originY + y) * sourceWidth
            for (x in sampleXs) {
                val centeredSource = grayscale(sourcePixels[row + originX + x]) - sourceMean
                difference += abs(centeredSource - centeredTemplate[index])
                index += 1
            }
        }
        return difference.toFloat() / index
    }

    private fun grayscale(color: Int): Int {
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return (red * 77 + green * 150 + blue * 29) shr 8
    }

    companion object {
        private const val MIN_TEMPLATE_SIDE = 12
        private const val MIN_CONTRAST = 8f
        private const val MIN_SAMPLES_PER_AXIS = 6
        private const val MAX_SAMPLES_PER_AXIS = 14

        fun create(template: Bitmap): TemplateMatcher? {
            val matcher = TemplateMatcher(template)
            return matcher.takeIf { it.isUsable }
        }
    }
}
