package com.clashdetector.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.clashdetector.model.ClashCard
import com.clashdetector.model.CardRegistry
import com.clashdetector.model.PlayedCard
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
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
 *   The cropped ROI is fed into a MobileNetV2-based TFLite classifier that was
 *   fine-tuned on Clash Royale card sprites.  If no trained model is bundled the
 *   detector falls back to colour-histogram template matching against pre-computed
 *   reference histograms.
 */
class CardDetector(private val context: Context) {

    companion object {
        private const val MODEL_FILE       = "models/card_classifier.tflite"
        private const val INPUT_SIZE       = 224
        private const val CONFIDENCE_THRESHOLD = 0.65f
        private const val PLACEMENT_BRIGHTNESS_THRESHOLD = 220
        private const val MIN_FLASH_AREA   = 400   // px²
        private const val ENEMY_SIDE_FRACTION = 0.0f to 0.50f // top 50 % = enemy side
    }

    // TFLite interpreter — may be null if model asset is not yet bundled
    private var interpreter: Interpreter? = null

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    // Precomputed reference histograms for fallback template matching
    private val referenceHistograms: Map<String, FloatArray> by lazy {
        loadReferenceHistograms()
    }

    // Labels in the order the TFLite model outputs them
    private val labels: List<String> by lazy { loadLabels() }

    init {
        tryLoadModel()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Analyse a full-screen [bitmap] and return every newly detected card play,
     * together with the normalised confidence score.
     *
     * @param bitmap  Full game screenshot (any resolution; will be scaled internally)
     * @param alreadySeenIds  Set of card play IDs seen this frame — used to avoid
     *                        duplicates when the flash lingers across multiple frames.
     */
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

            val (cardId, confidence) = classifyCard(crop)
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val card = CardRegistry.findById(cardId) ?: continue
            val playId = "${cardId}_${System.currentTimeMillis() / 500}" // de-dup within 500 ms
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

    /** Crop the top half of the screen (enemy arena side). */
    private fun cropEnemySide(src: Bitmap): Bitmap {
        val h = (src.height * ENEMY_SIDE_FRACTION.second).toInt()
        return Bitmap.createBitmap(src, 0, 0, src.width, h)
    }

    /**
     * Locate bright "placement ring" blobs in the image.
     * Returns bounding boxes in the coordinate space of [src].
     *
     * Algorithm:
     *   1. Convert to greyscale.
     *   2. Threshold to isolate very bright pixels (placement ring is near-white).
     *   3. Simple connected-component scan to collect blobs.
     *   4. Filter by minimum area and aspect ratio.
     */
    private fun findPlacementFlashes(src: Bitmap): List<RectF> {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Build brightness mask
        val bright = BooleanArray(w * h) { i ->
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            (r + g + b) / 3 > PLACEMENT_BRIGHTNESS_THRESHOLD
        }

        // Collect connected components via union-find-lite (row-scan labelling)
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

        // Build bounding boxes per component
        val boxes = HashMap<Int, IntArray>() // label -> [minX, minY, maxX, maxY, count]
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

    /**
     * Classify a cropped card-placement region.
     * Returns (cardId, confidence).
     */
    private fun classifyCard(crop: Bitmap): Pair<String, Float> {
        return if (interpreter != null) {
            classifyWithTFLite(crop)
        } else {
            classifyWithHistogram(crop)
        }
    }

    // --- TFLite path ---

    private fun classifyWithTFLite(crop: Bitmap): Pair<String, Float> {
        val interp = interpreter ?: return "" to 0f
        val tensorImage = TensorImage.fromBitmap(crop)
        val processed = imageProcessor.process(tensorImage)

        val outputSize = labels.size
        val output = Array(1) { FloatArray(outputSize) }
        interp.run(processed.buffer, output)

        val scores = output[0]
        val best = scores.indices.maxByOrNull { scores[it] } ?: return "" to 0f
        return labels[best] to scores[best]
    }

    private fun tryLoadModel() {
        try {
            val afd = context.assets.openFd(MODEL_FILE)
            val fis = FileInputStream(afd.fileDescriptor)
            val buf: MappedByteBuffer = fis.channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
            )
            interpreter = Interpreter(buf)
        } catch (_: Exception) {
            // Model not yet bundled — will use histogram fallback
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open("models/card_labels.txt")
                .bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            CardRegistry.ALL_CARDS.map { it.id }
        }
    }

    // --- Histogram fallback path ---

    /**
     * Compute a 64-bin HSV histogram for [bmp] and find the closest reference.
     */
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
            hist[bin] += hsv[2] // weight by value (brightness)
        }
        // L2-normalise
        val norm = hist.map { it * it }.sum().let { Math.sqrt(it.toDouble()).toFloat() }.coerceAtLeast(1e-6f)
        return FloatArray(bins) { hist[it] / norm }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (Math.sqrt((na * nb).toDouble()).toFloat().coerceAtLeast(1e-6f))
    }

    /**
     * Load pre-computed reference histograms from assets.
     * Falls back to empty map if not available (detection will skip histogram stage).
     */
    private fun loadReferenceHistograms(): Map<String, FloatArray> {
        return try {
            val json = context.assets.open("models/card_histograms.json").bufferedReader().readText()
            // Simple manual parse — avoids Gson dependency at this layer
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
