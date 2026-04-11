package com.clashdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView

class DetectorService : Service() {

    companion object {
        const val ACTION_START = "com.clashdetector.START"
        const val ACTION_STOP  = "com.clashdetector.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID  = "detector_channel"
        private const val NOTIF_ID    = 1
        private const val VIRTUAL_DISPLAY_NAME = "ClashDetector"
        private const val TICK_INTERVAL_MS = 200L
    }

    // Screen capture
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Display metrics
    private var screenWidth  = 1080
    private var screenHeight = 1920
    private var screenDpi    = 320

    // Overlay
    private var overlayView: android.view.View? = null
    private var elixirLabel: TextView? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    // Detection state
    private val elixirCounter = ElixirCounter()
    private val handler = Handler(Looper.getMainLooper())
    private var lastBrightness = 0f
    private var flashCooldownMs = 0L

    // Ticker runnable
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != -1 && resultData != null) {
                    startForegroundWithNotification()
                    readDisplayMetrics()
                    startCapture(resultCode, resultData)
                    showOverlay()
                    handler.post(tickRunnable)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        removeOverlay()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Notification / Foreground
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Elixir Detector",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Clash Royale elixir counter overlay"
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clash Royale Detector")
            .setContentText("Tracking enemy elixir…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // -------------------------------------------------------------------------
    // Screen capture
    // -------------------------------------------------------------------------

    private fun readDisplayMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth  = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            val dm = resources.displayMetrics
            screenDpi = dm.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth  = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDpi    = dm.densityDpi
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    // -------------------------------------------------------------------------
    // Overlay
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay, null)
        elixirLabel = overlayView!!.findViewById(R.id.elixir_value)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 80

        windowManager.addView(overlayView, params)
        updateOverlayText(elixirCounter.getInt())
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
            elixirLabel = null
        }
    }

    private fun updateOverlayText(elixir: Int) {
        elixirLabel?.let { tv ->
            tv.text = elixir.toString()
            val color = when {
                elixir < 5  -> Color.parseColor("#4CAF50")  // green
                elixir < 8  -> Color.parseColor("#FFC107")  // amber
                else        -> Color.parseColor("#F44336")  // red
            }
            tv.setTextColor(color)
        }
    }

    // -------------------------------------------------------------------------
    // Detection tick
    // -------------------------------------------------------------------------

    private fun tick() {
        elixirCounter.tick()

        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            try {
                analyzeFrame(image)
            } finally {
                image.close()
            }
        }

        updateOverlayText(elixirCounter.getInt())
    }

    /**
     * Analyse a single captured frame.
     *
     * Strategy:
     * 1. Read average brightness of top-half of screen (card play area).
     *    A sudden spike → card played → deduct average elixir cost.
     * 2. Read the enemy elixir bar colour strip (upper area in Clash Royale UI)
     *    to try to get a visual count (best-effort; bar position varies by device).
     */
    private fun analyzeFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride   = plane.rowStride

        // --- Flash detection (card play) ---
        val now = System.currentTimeMillis()
        if (now > flashCooldownMs) {
            val sampleRows    = 20
            val sampleCols    = 20
            val rowStep       = (screenHeight / 2) / sampleRows
            val colStep       = screenWidth / sampleCols
            var brightnessSum = 0f
            var sampleCount   = 0

            for (row in 0 until sampleRows) {
                for (col in 0 until sampleCols) {
                    val offset = row * rowStep * rowStride + col * colStep * pixelStride
                    if (offset + 2 < buffer.limit()) {
                        val r = (buffer.get(offset).toInt() and 0xFF).toFloat()
                        val g = (buffer.get(offset + 1).toInt() and 0xFF).toFloat()
                        val b = (buffer.get(offset + 2).toInt() and 0xFF).toFloat()
                        brightnessSum += (r + g + b) / 3f
                        sampleCount++
                    }
                }
            }

            val avgBrightness = if (sampleCount > 0) brightnessSum / sampleCount else 0f
            val delta = avgBrightness - lastBrightness
            lastBrightness = avgBrightness

            // Threshold: brightness spike > 30 points in one frame → card played
            if (delta > 30f && lastBrightness > 0f) {
                elixirCounter.cardPlayed(cost = 4f)
                flashCooldownMs = now + 800  // ignore next 800 ms to avoid double-counting
            }
        }

        // --- Elixir bar visual read ---
        // Clash Royale shows enemy elixir as a purple bar at approximately
        // y = 6% of screen height (top HUD), x = 35%..65% of width.
        // Each elixir unit is 10% of bar width. Count filled purple segments.
        val barY      = (screenHeight * 0.06f).toInt()
        val barXStart = (screenWidth  * 0.35f).toInt()
        val barXEnd   = (screenWidth  * 0.65f).toInt()
        val barWidth  = barXEnd - barXStart
        if (barWidth > 0 && barY < screenHeight) {
            var purpleCount = 0
            val segmentCount = 10
            for (seg in 0 until segmentCount) {
                val x = barXStart + (barWidth * seg / segmentCount) + (barWidth / segmentCount / 2)
                val offset = barY * rowStride + x * pixelStride
                if (offset + 2 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    // Purple/violet: high R, low G, high B
                    if (r > 100 && g < 80 && b > 100 && r + b > g * 3) {
                        purpleCount++
                    }
                }
            }
            if (purpleCount > 0) {
                // Blend visual reading with model: weight visual reading heavily if plausible
                val visual = purpleCount.toFloat()
                val model  = elixirCounter.get()
                // Only override if they agree within 2 or visual is clearly more reliable
                if (Math.abs(visual - model) <= 3f || purpleCount >= 8) {
                    elixirCounter.setElixir((visual * 0.7f + model * 0.3f))
                }
            }
        }

        // --- Double/Triple elixir phase detection ---
        // After minute 3 the bar background changes colour. For simplicity we use
        // a time-based heuristic keyed off elapsed service runtime.
        // (Real detection would read a timestamp overlay which varies too much.)
    }
}
