package com.clashdetector

/**
 * Tracks enemy elixir using an accounting model.
 *
 * Rules (mirrored from official Clash Royale behaviour):
 *  - Elixir regenerates at 2.8/s normally, 5.6/s in double-elixir, 8.4/s in triple-elixir
 *  - Maximum is 10 elixir
 *  - Spending a card costs elixir; we detect that by a screen flash
 *  - We start assuming the enemy has 5 elixir (mid-game assumption)
 */
class ElixirCounter {

    enum class Phase { NORMAL, DOUBLE, TRIPLE }

    private var elixir: Float = 5f
    private var phase: Phase = Phase.NORMAL
    private var lastUpdateMs: Long = System.currentTimeMillis()

    private val regenRate: Float
        get() = when (phase) {
            Phase.NORMAL -> 2.8f
            Phase.DOUBLE -> 5.6f
            Phase.TRIPLE -> 8.4f
        }

    /** Call every game loop tick to advance time-based elixir regeneration. */
    fun tick() {
        val now = System.currentTimeMillis()
        val dtSeconds = (now - lastUpdateMs) / 1000f
        lastUpdateMs = now
        elixir = (elixir + regenRate * dtSeconds).coerceIn(0f, 10f)
    }

    /**
     * Called when we detect a card play (screen flash).
     * [cost] is the estimated elixir cost of the played card (default 4 — average card cost).
     */
    fun cardPlayed(cost: Float = 4f) {
        elixir = (elixir - cost).coerceAtLeast(0f)
    }

    /** Override with visual reading from the enemy elixir bar if available. */
    fun setElixir(value: Float) {
        elixir = value.coerceIn(0f, 10f)
        lastUpdateMs = System.currentTimeMillis()
    }

    fun setPhase(p: Phase) {
        phase = p
        lastUpdateMs = System.currentTimeMillis()
    }

    /** Returns the current estimated elixir (0–10). */
    fun get(): Float = elixir

    /** Returns integer version for display. */
    fun getInt(): Int = elixir.toInt().coerceIn(0, 10)
}
