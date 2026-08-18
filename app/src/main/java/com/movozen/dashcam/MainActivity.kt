package com.movozen.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, StreamService.Listener {

    private lateinit var preview: SurfaceView
    private lateinit var rollInput: EditText
    private lateinit var urlFront: TextView
    private lateinit var urlBack: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statsText: TextView
    private lateinit var recBadge: View
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnDual: Button
    private lateinit var btnVariant: Button

    private var service: StreamService? = null
    private var bound = false
    private var surfaceReady = false
    private var permsGranted = false
    private var dualWanted = false
    private var variantIndex = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamService.LocalBinder).service
            service?.listener = this@MainActivity
            bound = true
            maybeStartPreview()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.listener = null
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permsGranted = result[Manifest.permission.CAMERA] == true &&
                result[Manifest.permission.RECORD_AUDIO] == true
        if (permsGranted) {
            appendLog("Camera + microphone permission granted")
            maybeStartPreview()
        } else {
            appendLog("PERMISSION DENIED — camera/mic are required")
            Toast.makeText(this, "Camera and microphone are required", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        rollInput = findViewById(R.id.rollInput)
        urlFront = findViewById(R.id.urlFront)
        urlBack = findViewById(R.id.urlBack)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        statsText = findViewById(R.id.statsText)
        recBadge = findViewById(R.id.recBadge)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnDual = findViewById(R.id.btnDual)
        btnVariant = findViewById(R.id.btnVariant)

        preview.holder.addCallback(this)

        rollInput.setText("BTECH2500223")
        refreshUrls()
        rollInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshUrls() }

        btnVariant.setOnClickListener {
            val variants = Config.keyVariants(rollInput.text.toString())
            if (variants.isEmpty()) return@setOnClickListener
            variantIndex = (variantIndex + 1) % variants.size
            refreshUrls()
            appendLog("Stream key set to: ${variants[variantIndex]}")
        }

        btnStart.setOnClickListener {
            val key = currentKey()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter your roll number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!permsGranted) {
                requestPerms()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
            service?.start(key, dualWanted)
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        }

        btnStop.setOnClickListener {
            service?.stop()
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            recBadge.visibility = View.INVISIBLE
            statsText.text = "—"
        }

        btnSwitch.setOnClickListener { service?.switchCamera() }

        btnDual.setOnClickListener {
            dualWanted = !dualWanted
            btnDual.text = if (dualWanted) "DUAL: ON" else "DUAL: OFF"
            btnDual.setBackgroundResource(
                if (dualWanted) R.drawable.btn_accent else R.drawable.btn_ghost
            )
            if (dualWanted && service?.isStreaming() == true) service?.startBack()
            appendLog(if (dualWanted) "Dual-camera mode armed (Tier 2)" else "Dual-camera mode off")
        }

        btnStop.isEnabled = false
        appendLog("MoboSafe Pocket Dashcam ready")
        appendLog("Viewer: ${Config.VIEWER_URL}")

        requestPerms()
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            service?.listener = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        maybeStartPreview()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // Screen lock / background: drop preview only. Streaming continues in the service.
        service?.releasePreview()
    }

    private fun maybeStartPreview() {
        if (permsGranted && surfaceReady && bound) service?.initPreview(preview)
    }

    private fun requestPerms() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permsGranted = true
            maybeStartPreview()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun currentKey(): String {
        val variants = Config.keyVariants(rollInput.text.toString())
        if (variants.isEmpty()) return ""
        return variants[variantIndex.coerceIn(0, variants.size - 1)]
    }

    private fun refreshUrls() {
        val key = currentKey()
        urlFront.text = Config.frontUrl(key)
        urlBack.text = Config.backUrl(key)
        btnVariant.text = "KEY: $key"
    }

    // ------------------------------------------------------------------ listener

    override fun onLog(line: String) = appendLog(line)

    override fun onState(state: StreamService.State, detail: String) {
        val (label, color) = when (state) {
            StreamService.State.IDLE -> "OFFLINE" to R.color.muted
            StreamService.State.CONNECTING -> "CONNECTING" to R.color.amber
            StreamService.State.LIVE -> "LIVE" to R.color.live
            StreamService.State.RETRYING -> "RECONNECTING" to R.color.amber
            StreamService.State.ERROR -> "ERROR" to R.color.rec
        }
        statusText.text = label
        statusText.setTextColor(ContextCompat.getColor(this, color))
        statusDot.setBackgroundResource(
            when (state) {
                StreamService.State.LIVE -> R.drawable.dot_live
                StreamService.State.ERROR -> R.drawable.dot_rec
                StreamService.State.IDLE -> R.drawable.dot_muted
                else -> R.drawable.dot_amber
            }
        )
        statusDetail.text = detail
        recBadge.visibility = if (state == StreamService.State.LIVE) View.VISIBLE else View.INVISIBLE
        if (state == StreamService.State.IDLE) {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    override fun onStats(kbps: Long, uptimeSeconds: Long, dualActive: Boolean) {
        val up = String.format(Locale.US, "%02d:%02d", uptimeSeconds / 60, uptimeSeconds % 60)
        statsText.text = "UP $up   ·   $kbps kbps   ·   ${if (dualActive) "2 FEEDS" else "1 FEED"}"
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logView.append("${timeFmt.format(Date())}  $line\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
