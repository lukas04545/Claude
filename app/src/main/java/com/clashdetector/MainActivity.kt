package com.clashdetector

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.clashdetector.service.OverlayService
import com.clashdetector.service.ScreenCaptureService

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    companion object {
        private const val REQ_OVERLAY   = 1001
        private const val REQ_CAPTURE   = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        btnStart   = findViewById(R.id.btn_start)
        btnStop    = findViewById(R.id.btn_stop)

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener  { onStopClicked()  }

        updateUi(running = false)
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_OVERLAY)
        } else {
            requestProjectionPermission()
        }
    }

    private fun onStopClicked() {
        stopCapture()
    }

    private fun requestProjectionPermission() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQ_CAPTURE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    requestProjectionPermission()
                } else {
                    Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
                }
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    startCapture(resultCode, data)
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        Intent(this, ScreenCaptureService::class.java).also {
            it.action = ScreenCaptureService.ACTION_START
            it.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            it.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            startForegroundService(it)
        }
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
