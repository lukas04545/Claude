package com.clashdetector

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.clashdetector.service.OverlayService
import com.clashdetector.service.ScreenCaptureService

/**
 * Entry point.
 *
 * Flow:
 *  1. Request SYSTEM_ALERT_WINDOW permission (overlay).
 *  2. Request MediaProjection permission (screen capture).
 *  3. Start [ScreenCaptureService] with the projection token.
 *  4. Start [OverlayService].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCapture(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestProjectionPermission()
        } else {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        btnStart   = findViewById(R.id.btn_start)
        btnStop    = findViewById(R.id.btn_stop)

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener  { onStopClicked() }

        updateUi(running = false)
    }

    // -------------------------------------------------------------------------

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            requestProjectionPermission()
        }
    }

    private fun onStopClicked() {
        stopCapture()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestProjectionPermission() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        // Start screen capture service
        Intent(this, ScreenCaptureService::class.java).also {
            it.action = ScreenCaptureService.ACTION_START
            it.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            it.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            startForegroundService(it)
        }

        // Start overlay
        Intent(this, OverlayService::class.java).also {
            it.action = OverlayService.ACTION_SHOW
            startForegroundService(it)
        }

        updateUi(running = true)
        statusText.text = "Detector active — switch to Clash Royale"
    }

    private fun stopCapture() {
        Intent(this, ScreenCaptureService::class.java).also {
            it.action = ScreenCaptureService.ACTION_STOP
            startService(it)
        }
        Intent(this, OverlayService::class.java).also {
            it.action = OverlayService.ACTION_HIDE
            startService(it)
        }
        updateUi(running = false)
        statusText.text = "Detector stopped"
    }

    private fun updateUi(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled  = running
        statusText.text    = if (running) "Running…" else "Ready"
    }
}
