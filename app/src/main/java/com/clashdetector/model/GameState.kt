package com.clashdetector.model

/**
 * Snapshot of the detected game state at a given moment.
 */
data class GameState(
    val enemyElixir: Float = 0f,
    val recentEnemyCards: List<PlayedCard> = emptyList(),
    val elixirAdvantage: Float = 0f,     // positive = we have more elixir
    val isDoublElixir: Boolean = false,
    val isTrippleElixir: Boolean = false,
    val gameTimeSeconds: Int = 0,
    val elixirRefillRate: Float = DEFAULT_REFILL_RATE
) {
    companion object {
        const val DEFAULT_REFILL_RATE = 2.8f      // elixir per second in normal time
        const val DOUBLE_ELIXIR_RATE = 5.6f
        const val TRIPLE_ELIXIR_RATE = 8.4f
        const val MAX_ELIXIR = 10f
    }
}

/**
 * A card that was detected as played by the enemy.
 */
data class PlayedCard(
    val card: ClashCard,
    val playedAtMs: Long,
    val confidence: Float,
    val position: CardPosition? = null
)

/**
 * Approximate arena position where the card was played.
 */
data class CardPosition(
    val lane: Lane,
    val depth: Depth
)

enum class Lane { LEFT, CENTER, RIGHT }
enum class Depth { NEAR, MID, BACK }
