package com.clashdetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

import com.clashdetector.R
import com.clashdetector.detection.CardDetector
import com.clashdetector.detection.ElixirTracker
import com.clashdetector.model.GameState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Foreground service that:
 *  1. Holds a [MediaProjection] token to capture the screen.
 *  2. Creates a [VirtualDisplay] + [ImageReader] pipeline.
 *  3. On each captured frame: runs [CardDetector] + [ElixirTracker].
 *  4. Emits [GameState] updates via a shared flow consumed by [OverlayService].
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START  = "com.clashdetector.START_CAPTURE"
        const val ACTION_STOP   = "com.clashdetector.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE   = "result_code"
        const val EXTRA_RESULT_DATA   = "result_data"

        private const val NOTIFICATION_ID   = 1001
        private const val CHANNEL_ID        = "capture_channel"
        private const val CAPTURE_INTERVAL_MS = 250L  // analyse 4 fps — enough for card detection

        // Singleton flow so OverlayService can observe updates
        private val _gameStateFlow = MutableSharedFlow<GameState>(replay = 1)
        val gameStateFlow = _gameStateFlow.asSharedFlow()
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var cardDetector: CardDetector
    private val elixirTracker = ElixirTracker()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val seenCardIds  = mutableSetOf<String>()

    private var screenWidth  = 1080
    private var screenHeight = 2340
    private var screenDpi    = 420

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        cardDetector = CardDetector(this)
        readDisplayMetrics()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != 0 && resultData != null) {
                    startCapture(resultCode, resultData)
                }
            }
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Capture pipeline
    // -------------------------------------------------------------------------

    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ClashDetectorDisplay",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        elixirTracker.reset()

        serviceScope.launch {
            while (isActive) {
                processFrame()
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private fun stopCapture() {
        serviceScope.coroutineContext.cancelChildren()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay  = null
        imageReader     = null
        mediaProjection = null
    }

    private suspend fun processFrame() {
        val bitmap = acquireLatestBitmap() ?: return

        val ownElixir = elixirTracker.readOwnElixirBar(bitmap)

        // Card detection (runs on Default dispatcher — CPU-bound)
        val newPlays = cardDetector.detect(bitmap, seenCardIds)
        for (play in newPlays) {
            val key = "${play.card.id}_${play.playedAtMs / 500}"
            seenCardIds.add(key)
            elixirTracker.recordPlay(play)
        }

        // Purge old seen-ids (keep memory bounded)
        if (seenCardIds.size > 200) seenCardIds.clear()

        val state = elixirTracker.tick(bitmap, ownElixir)
        _gameStateFlow.emit(state)

        bitmap.recycle()
    }

    /** Acquire latest image from the reader and convert to a [Bitmap]. */
    private fun acquireLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * screenWidth

            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            // Crop to exact screen size if row padding was added
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                bmp.recycle()
                cropped
            } else bmp
        } catch (_: Exception) {
            null
        } finally {
            image?.close()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun readDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            screenWidth  = bounds.width()
            screenHeight = bounds.height()
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(dm)
            screenDpi = dm.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth  = dm.widthPixels
            screenHeight = dm.heightPixels
            screenDpi    = dm.densityDpi
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clash Royale AI Detector")
            .setContentText("Analysing enemy cards…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
