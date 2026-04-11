package com.clashdetector

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    companion object {
        private const val REQ_OVERLAY = 1001
        private const val REQ_CAPTURE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val root = findViewById<ViewGroup>(android.R.id.content)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(sys.left, sys.top, sys.right, sys.bottom)
                insets
            }
        }

        statusText = findViewById(R.id.status_text)
        btnStart   = findViewById(R.id.btn_start)
        btnStop    = findViewById(R.id.btn_stop)

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener  { onStopClicked()  }

        setUiState(running = false)
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
            requestScreenCapture()
        }
    }

    private fun onStopClicked() {
        Intent(this, DetectorService::class.java).also {
            it.action = DetectorService.ACTION_STOP
            startService(it)
        }
        setUiState(running = false)
        statusText.text = getString(R.string.status_stopped)
    }

    private fun requestScreenCapture() {
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) requestScreenCapture()
                else Toast.makeText(this, R.string.permission_overlay_denied, Toast.LENGTH_LONG).show()
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    startDetector(resultCode, data)
                } else {
                    Toast.makeText(this, R.string.permission_capture_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startDetector(resultCode: Int, data: Intent) {
        Intent(this, DetectorService::class.java).also {
            it.action = DetectorService.ACTION_START
            it.putExtra(DetectorService.EXTRA_RESULT_CODE, resultCode)
            it.putExtra(DetectorService.EXTRA_RESULT_DATA, data)
            startForegroundService(it)
        }
        setUiState(running = true)
        statusText.text = getString(R.string.status_active)
    }

    private fun setUiState(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled  = running
        if (!running) statusText.text = getString(R.string.status_ready)
    }
}
