package com.clashdetector.detection

import android.graphics.Bitmap
import android.graphics.Color
import com.clashdetector.model.GameState
import com.clashdetector.model.PlayedCard
import kotlin.math.abs
import kotlin.math.min

/**
 * Tracks the enemy's estimated elixir in real-time.
 *
 * Two complementary methods are combined:
 *
 * A) **Visual read** (primary):
 *    The game renders the *own* elixir bar at the bottom of the screen as a row
 *    of 10 pink/purple segments.  The *enemy* bar is not directly visible.
 *    However, if screen mirroring info is available we read the enemy bar from
 *    the top; otherwise we rely on method B.
 *
 * B) **Accounting model** (always active):
 *    - Elixir refills at a known rate (2.8 / s normal, 5.6 / s double, 8.4 / s triple).
 *    - Each time we detect a card play we deduct [card.elixirCost] from the model.
 *    - The model is clamped to [0, 10].
 *
 * The visual read is used to periodically *re-sync* the accounting model so
 * errors don't accumulate.
 */
class ElixirTracker {

    companion object {
        // HSV hue range for the elixir bar colour (purple-pink)
        private const val ELIXIR_HUE_MIN = 270f
        private const val ELIXIR_HUE_MAX = 330f

        // Elixir bar is in the bottom ~8 % of the screen, full width
        private const val BAR_TOP_FRACTION    = 0.915f
        private const val BAR_BOTTOM_FRACTION = 0.945f

        // Similarly the *enemy* bar sits in the top ~8 %
        private const val ENEMY_BAR_TOP_FRACTION    = 0.055f
        private const val ENEMY_BAR_BOTTOM_FRACTION = 0.085f

        private const val MAX_ELIXIR = 10f
    }

    // ---- Accounting model state ----
    private var modelElixir: Float = 5f
    private var lastUpdateMs: Long = System.currentTimeMillis()
    private var refillRate: Float = GameState.DEFAULT_REFILL_RATE

    private var gameTimeSeconds: Int = 0
    private var gameStartMs: Long = System.currentTimeMillis()

    // ---- Pending plays (we apply them on the next tick) ----
    private val pendingDeductions = mutableListOf<Float>()

    // ---- History for the overlay ----
    private val recentPlays = ArrayDeque<PlayedCard>(8)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Call once when a new match starts. */
    fun reset() {
        modelElixir = 5f
        lastUpdateMs = System.currentTimeMillis()
        gameStartMs = lastUpdateMs
        gameTimeSeconds = 0
        pendingDeductions.clear()
        recentPlays.clear()
        refillRate = GameState.DEFAULT_REFILL_RATE
    }

    /**
     * Record a detected enemy card play.  The elixir cost is queued for
     * deduction on the next [tick].
     */
    fun recordPlay(play: PlayedCard) {
        pendingDeductions.add(play.card.elixirCost.toFloat())
        if (recentPlays.size >= 8) recentPlays.removeLast()
        recentPlays.addFirst(play)
    }

    /**
     * Main update method — call every frame (or at least every ~100 ms).
     *
     * @param screenshot  Current full-screen bitmap (may be null if unavailable).
     * @param ownElixir   Own elixir read from the UI, if available (0–10).
     * @return            The latest [GameState] with estimated enemy elixir.
     */
    fun tick(screenshot: Bitmap? = null, ownElixir: Float? = null): GameState {
        val now = System.currentTimeMillis()
        val dtSeconds = (now - lastUpdateMs) / 1000f
        lastUpdateMs = now

        gameTimeSeconds = ((now - gameStartMs) / 1000).toInt()
        updateRefillRate(gameTimeSeconds)

        // Refill
        modelElixir = min(MAX_ELIXIR, modelElixir + refillRate * dtSeconds)

        // Apply deductions from detected plays
        for (cost in pendingDeductions) {
            modelElixir = (modelElixir - cost).coerceAtLeast(0f)
        }
        pendingDeductions.clear()

        // Try to re-sync from visual read
        screenshot?.let {
            val visual = readEnemyElixirBar(it)
            if (visual != null) {
                // Blend: trust visual more than model
                modelElixir = visual * 0.8f + modelElixir * 0.2f
            }
        }

        val elixirAdvantage = (ownElixir ?: modelElixir) - modelElixir

        return GameState(
            enemyElixir = modelElixir.coerceIn(0f, MAX_ELIXIR),
            recentEnemyCards = recentPlays.toList(),
            elixirAdvantage = elixirAdvantage,
            isDoublElixir = refillRate >= GameState.DOUBLE_ELIXIR_RATE && refillRate < GameState.TRIPLE_ELIXIR_RATE,
            isTrippleElixir = refillRate >= GameState.TRIPLE_ELIXIR_RATE,
            gameTimeSeconds = gameTimeSeconds,
            elixirRefillRate = refillRate
        )
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun updateRefillRate(seconds: Int) {
        refillRate = when {
            seconds >= 180 -> GameState.TRIPLE_ELIXIR_RATE // 3:00 sudden death
            seconds >= 120 -> GameState.DOUBLE_ELIXIR_RATE // 2:00 double elixir
            else           -> GameState.DEFAULT_REFILL_RATE
        }
    }

    /**
     * Attempt to read the enemy elixir from the top bar of the screenshot.
     *
     * The bar is a horizontal strip of coloured pixels.  We sample a horizontal
     * line through the middle of the bar region and count coloured (non-grey)
     * segments to estimate fill level 0–10.
     */
    private fun readEnemyElixirBar(bmp: Bitmap): Float? {
        val w = bmp.width
        val topY    = (bmp.height * ENEMY_BAR_TOP_FRACTION).toInt()
        val bottomY = (bmp.height * ENEMY_BAR_BOTTOM_FRACTION).toInt()
        if (topY >= bottomY || bottomY >= bmp.height) return null

        val midY = (topY + bottomY) / 2
        val pixels = IntArray(w)
        bmp.getPixels(pixels, 0, w, 0, midY, w, 1)

        var filledCount = 0
        val hsv = FloatArray(3)
        for (p in pixels) {
            Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
            val hue = hsv[0]; val sat = hsv[1]; val value = hsv[2]
            if (sat > 0.35f && value > 0.3f && hue in ELIXIR_HUE_MIN..ELIXIR_HUE_MAX) {
                filledCount++
            }
        }

        val fillFraction = filledCount.toFloat() / w
        // The bar spans roughly 60 % of the screen width when full
        val normalised = (fillFraction / 0.60f).coerceIn(0f, 1f)
        return normalised * MAX_ELIXIR
    }

    /**
     * Read the *own* elixir bar at the bottom of the screen.
     * Returns a value 0–10 or null if the bar cannot be read.
     */
    fun readOwnElixirBar(bmp: Bitmap): Float? {
        val w = bmp.width
        val topY    = (bmp.height * BAR_TOP_FRACTION).toInt()
        val bottomY = (bmp.height * BAR_BOTTOM_FRACTION).toInt()
        if (topY >= bottomY || bottomY >= bmp.height) return null

        val midY = (topY + bottomY) / 2
        val pixels = IntArray(w)
        bmp.getPixels(pixels, 0, w, 0, midY, w, 1)

        var filledCount = 0
        val hsv = FloatArray(3)
        for (p in pixels) {
            Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
            val hue = hsv[0]; val sat = hsv[1]; val value = hsv[2]
            if (sat > 0.35f && value > 0.3f && hue in ELIXIR_HUE_MIN..ELIXIR_HUE_MAX) {
                filledCount++
            }
        }

        val fillFraction = filledCount.toFloat() / w
        val normalised = (fillFraction / 0.60f).coerceIn(0f, 1f)
        return normalised * MAX_ELIXIR
    }
}
