package com.clashdetector.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.clashdetector.model.CardRegistry
import com.clashdetector.model.PlayedCard
import kotlin.math.max
import kotlin.math.min

/**
 * Detects Clash Royale cards being placed by the enemy using a two-stage approach:
 *
 * Stage 1 – Region of Interest detection:
 *   Scans the upper half of the arena (enemy side) for "placement flash" — the
 *   bright ring that appears when a card is placed.  This gives a bounding box.
 *
 * Stage 2 – Card classification:
 *   Colour-histogram template matching against pre-computed reference histograms.
 */
class CardDetector(private val context: Context) {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.65f
        private const val PLACEMENT_BRIGHTNESS_THRESHOLD = 220
        private const val MIN_FLASH_AREA   = 400   // px²
        private const val ENEMY_SIDE_FRACTION = 0.50f // top 50 % = enemy side
    }

    // Precomputed reference histograms for template matching
    private val referenceHistograms: Map<String, FloatArray> by lazy {
        loadReferenceHistograms()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun detect(bitmap: Bitmap, alreadySeenIds: Set<String> = emptySet()): List<PlayedCard> {
        val enemyRegion = cropEnemySide(bitmap)
        val flashRegions = findPlacementFlashes(enemyRegion)

        if (flashRegions.isEmpty()) return emptyList()

        val detections = mutableListOf<PlayedCard>()
        for (region in flashRegions) {
            val crop = Bitmap.createBitmap(
                enemyRegion,
                region.left.toInt().coerceAtLeast(0),
                region.top.toInt().coerceAtLeast(0),
                (region.width()).toInt().coerceAtMost(enemyRegion.width - region.left.toInt()),
                (region.height()).toInt().coerceAtMost(enemyRegion.height - region.top.toInt())
            )

            val (cardId, confidence) = classifyWithHistogram(crop)
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val card = CardRegistry.findById(cardId) ?: continue
            val playId = "${cardId}_${System.currentTimeMillis() / 500}"
            if (playId in alreadySeenIds) continue

            detections.add(
                PlayedCard(
                    card = card,
                    playedAtMs = System.currentTimeMillis(),
                    confidence = confidence
                )
            )
        }
        return detections
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun cropEnemySide(src: Bitmap): Bitmap {
        val h = (src.height * ENEMY_SIDE_FRACTION).toInt()
        return Bitmap.createBitmap(src, 0, 0, src.width, h)
    }

    private fun findPlacementFlashes(src: Bitmap): List<RectF> {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val bright = BooleanArray(w * h) { i ->
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            (r + g + b) / 3 > PLACEMENT_BRIGHTNESS_THRESHOLD
        }

        val label = IntArray(w * h) { -1 }
        var nextLabel = 0
        val parent = mutableListOf<Int>()

        fun root(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var cur = x
            while (parent[cur] != cur) { val tmp = parent[cur]; parent[cur] = r; cur = tmp }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[ra] = rb
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!bright[idx]) continue
                val above = if (y > 0 && bright[(y - 1) * w + x]) label[(y - 1) * w + x] else -1
                val left  = if (x > 0 && bright[y * w + x - 1]) label[y * w + x - 1]       else -1
                label[idx] = when {
                    above >= 0 && left >= 0 -> { union(above, left); root(above) }
                    above >= 0 -> root(above)
                    left  >= 0 -> root(left)
                    else -> { parent.add(nextLabel); nextLabel++ }
                }
            }
        }

        val boxes = HashMap<Int, IntArray>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (label[idx] < 0) continue
                val lbl = root(label[idx])
                val box = boxes.getOrPut(lbl) { intArrayOf(w, h, 0, 0, 0) }
                box[0] = min(box[0], x); box[1] = min(box[1], y)
                box[2] = max(box[2], x); box[3] = max(box[3], y)
                box[4]++
            }
        }

        return boxes.values
            .filter { it[4] >= MIN_FLASH_AREA }
            .map { b ->
                val cx = (b[0] + b[2]) / 2f
                val cy = (b[1] + b[3]) / 2f
                val half = max(b[2] - b[0], b[3] - b[1]) * 1.5f / 2f
                RectF(cx - half, cy - half, cx + half, cy + half)
            }
    }

    private fun classifyWithHistogram(bmp: Bitmap): Pair<String, Float> {
        val hist = computeHsvHistogram(bmp, bins = 64)
        var bestId = ""
        var bestSim = -1f
        for ((id, ref) in referenceHistograms) {
            val sim = cosineSimilarity(hist, ref)
            if (sim > bestSim) { bestSim = sim; bestId = id }
        }
        return bestId to bestSim
    }

    private fun computeHsvHistogram(bmp: Bitmap, bins: Int): FloatArray {
        val hist = FloatArray(bins)
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        for (p in pixels) {
            Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
            val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            hist[bin] += hsv[2]
        }
        val norm = hist.map { it * it }.sum().let { Math.sqrt(it.toDouble()).toFloat() }.coerceAtLeast(1e-6f)
        return FloatArray(bins) { hist[it] / norm }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (Math.sqrt((na * nb).toDouble()).toFloat().coerceAtLeast(1e-6f))
    }

    private fun loadReferenceHistograms(): Map<String, FloatArray> {
        return try {
            val json = context.assets.open("models/card_histograms.json").bufferedReader().readText()
            val result = mutableMapOf<String, FloatArray>()
            val entries = json.trim().removePrefix("{").removeSuffix("}").split("],")
            for (entry in entries) {
                val (key, values) = entry.split(":[")
                val id = key.trim().removeSurrounding("\"")
                val floats = values.removeSuffix("]").split(",").mapNotNull { it.trim().toFloatOrNull() }
                result[id] = floats.toFloatArray()
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
