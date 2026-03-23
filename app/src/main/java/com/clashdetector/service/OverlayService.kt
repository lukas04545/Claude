package com.clashdetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.clashdetector.R
import com.clashdetector.model.GameState
import com.clashdetector.model.PlayedCard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Draws a floating, draggable overlay on top of Clash Royale that shows:
 *  - Estimated enemy elixir (0–10) as a coloured bar + number
 *  - Last 4 cards the enemy played (icon + name + elixir cost)
 *  - Elixir advantage indicator (± value)
 *  - Double / Triple elixir indicator
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.clashdetector.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.clashdetector.HIDE_OVERLAY"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_channel"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Drag state
    private var initialX = 0; private var initialY = 0
    private var touchX = 0f;  private var touchY = 0f

    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overlay management
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 200
        }

        makeDraggable(view, params)
        windowManager.addView(view, params)
        overlayView = view

        // Start observing game state
        serviceScope.launch {
            ScreenCaptureService.gameStateFlow.collectLatest { state ->
                updateOverlay(view, state)
            }
        }
    }

    private fun hideOverlay() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        serviceScope.coroutineContext.cancelChildren()
    }

    // -------------------------------------------------------------------------
    // UI update
    // -------------------------------------------------------------------------

    private fun updateOverlay(root: View, state: GameState) {
        // --- Elixir bar ---
        val elixirBar   = root.findViewById<View>(R.id.elixir_fill)
        val elixirText  = root.findViewById<TextView>(R.id.elixir_value)
        val elixirLabel = root.findViewById<TextView>(R.id.elixir_label)

        val fraction = state.enemyElixir / 10f
        elixirBar.layoutParams = (elixirBar.layoutParams as LinearLayout.LayoutParams).also {
            it.weight = fraction
        }
        elixirText.text  = "%.1f".format(state.enemyElixir)
        elixirLabel.text = when {
            state.isTrippleElixir -> "3x ELIXIR"
            state.isDoublElixir   -> "2x ELIXIR"
            else                  -> "Enemy Elixir"
        }

        // Colour: green (low) -> yellow -> red (high)
        val barColor = elixirBarColor(state.enemyElixir)
        elixirBar.setBackgroundColor(barColor)

        // --- Advantage ---
        val advantageText = root.findViewById<TextView>(R.id.elixir_advantage)
        val adv = state.elixirAdvantage
        advantageText.text = when {
            adv > 0.5f  -> "+%.1f ▲".format(adv)
            adv < -0.5f -> "%.1f ▼".format(adv)
            else        -> "≈ even"
        }
        advantageText.setTextColor(
            when {
                adv > 0.5f  -> 0xFF4CAF50.toInt()
                adv < -0.5f -> 0xFFF44336.toInt()
                else        -> 0xFFFFFFFF.toInt()
            }
        )

        // --- Recent cards ---
        val cardContainer = root.findViewById<LinearLayout>(R.id.card_list)
        cardContainer.removeAllViews()
        for (play in state.recentEnemyCards.take(4)) {
            addCardRow(cardContainer, play)
        }
    }

    private fun addCardRow(container: LinearLayout, play: PlayedCard) {
        val inflater = LayoutInflater.from(this)
        val row = inflater.inflate(R.layout.card_row_item, container, false)

        row.findViewById<TextView>(R.id.card_name).text  = play.card.name
        row.findViewById<TextView>(R.id.card_elixir).text = "${play.card.elixirCost}"
        row.findViewById<TextView>(R.id.card_type).text   = play.card.type.name.lowercase()
            .replaceFirstChar { it.uppercase() }

        // Confidence dot colour
        val dot = row.findViewById<View>(R.id.confidence_dot)
        dot.setBackgroundColor(
            when {
                play.confidence > 0.85f -> 0xFF4CAF50.toInt()
                play.confidence > 0.70f -> 0xFFFFC107.toInt()
                else                    -> 0xFFFF9800.toInt()
            }
        )

        // Elixir cost badge colour
        val badge = row.findViewById<TextView>(R.id.card_elixir)
        badge.setBackgroundColor(elixirBadgeColor(play.card.elixirCost))

        container.addView(row)
    }

    private fun elixirBarColor(elixir: Float): Int {
        return when {
            elixir >= 8f -> 0xFFF44336.toInt()   // red
            elixir >= 5f -> 0xFFFFC107.toInt()   // amber
            else         -> 0xFF4CAF50.toInt()   // green
        }
    }

    private fun elixirBadgeColor(cost: Int): Int = when (cost) {
        1    -> 0xFF66BB6A.toInt()
        2    -> 0xFF4CAF50.toInt()
        3    -> 0xFF42A5F5.toInt()
        4    -> 0xFF7E57C2.toInt()
        5    -> 0xFFAB47BC.toInt()
        6    -> 0xFFE91E63.toInt()
        7, 8 -> 0xFFF44336.toInt()
        else -> 0xFF9C27B0.toInt()
    }

    // -------------------------------------------------------------------------
    // Drag support
    // -------------------------------------------------------------------------

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX;  touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clash Detector Overlay")
            .setContentText("Overlay active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
