package com.swip.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.swip.app.R
import com.swip.app.databinding.ActivityMainBinding
import com.swip.app.service.SwipAccessibilityService
import com.swip.app.service.SwipService

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var audioManager: AudioManager
    private var proxSensor: Sensor? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var swipService: SwipService? = null
    private val handler = Handler(Looper.getMainLooper())

    // Gesture state
    private var lastNear = 0L
    private var waveCount = 0
    private var isNear = false

    // UI state
    private var isListening = false
    private var isDriving = false
    private var skipVisible = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            swipService = (b as? SwipService.LocalBinder)?.get()
            setStatus("Ready")
        }
        override fun onServiceDisconnected(n: ComponentName?) { swipService = null }
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.action) {
                "swip.SKIP_VISIBLE" -> showSkip(true)
                "swip.SKIP_GONE"    -> showSkip(false)
                "swip.AD_SKIPPED"   -> { showSkip(false); toast("Ad skipped!") }
                "swip.ACCESSIBILITY_ON" -> refreshAccessibilityBadge()
            }
        }
    }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else toast("Mic permission needed for voice control")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setupUI()
        startSwipService()
        registerBroadcasts()
        refreshAccessibilityBadge()
    }

    // ── UI SETUP ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        // Voice button
        binding.btnVoice.setOnClickListener {
            if (isListening) stopListening() else checkMicThenListen()
        }

        // Skip button (shown when ad detected)
        binding.btnSkip.setOnClickListener { doSkip() }

        // YouTube
        binding.btnYoutube.setOnClickListener { openYouTube() }

        // Driving mode
        binding.btnDrive.setOnClickListener { toggleDrive() }

        // Accessibility setup
        binding.cardAccessibility.setOnClickListener { showAccessibilityDialog() }

        // Wave hint
        binding.tvWaveHint.text = "Wave once = Play/Pause  \u2022  Wave twice = Skip"

        showSkip(false)
    }

    // ── SERVICE ──────────────────────────────────────────────────────────────

    private fun startSwipService() {
        val i = Intent(this, SwipService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(i) else startService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    // ── BROADCASTS ───────────────────────────────────────────────────────────

    private fun registerBroadcasts() {
        val f = IntentFilter().apply {
            addAction("swip.SKIP_VISIBLE")
            addAction("swip.SKIP_GONE")
            addAction("swip.AD_SKIPPED")
            addAction("swip.ACCESSIBILITY_ON")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(events, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(events, f)
    }

    // ── SKIP BUTTON ──────────────────────────────────────────────────────────

    private fun showSkip(show: Boolean) {
        skipVisible = show
        binding.btnSkip.visibility = if (show) View.VISIBLE else View.GONE
        if (show) setStatus("Ad detected — say Skip or tap button!")
        else if (isDriving) setStatus("Driving mode active")
        else setStatus("Ready")
    }

    private fun doSkip() {
        val skipped = SwipAccessibilityService.instance?.doSkip() ?: false
        if (!skipped) {
            sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            toast("Skip sent")
        }
    }

    // ── VOICE ────────────────────────────────────────────────────────────────

    private fun checkMicThenListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startListening()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition not available on this device")
            return
        }

        stopListening()
        isListening = true
        setVoiceUI(true)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val words = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""
                handleCommand(words)
                setVoiceUI(false)
                isListening = false
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Could not hear a command, try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error - check connection"
                    else -> "Voice error ($error)"
                }
                toast(msg)
                setVoiceUI(false)
                isListening = false
            }
            override fun onReadyForSpeech(p: android.os.Bundle?) { setStatus("Listening...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {
                // Animate mic based on volume
                val scale = 1f + (v.coerceIn(0f, 10f) / 10f) * 0.15f
                binding.btnVoice.scaleX = scale
                binding.btnVoice.scaleY = scale
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(r: android.os.Bundle?) {}
            override fun onEvent(t: Int, b: android.os.Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
        isListening = false
        setVoiceUI(false)
    }

    private fun handleCommand(cmd: String) {
        if (cmd.isEmpty()) return
        setStatus("Heard: $cmd")

        when {
            cmd.contains("skip") -> {
                val ok = SwipAccessibilityService.instance?.doSkip() ?: false
                if (!ok) sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                toast("Skipped!")
            }
            cmd.contains("play") && !cmd.contains("pause") -> {
                sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                toast("Playing")
            }
            cmd.contains("pause") || cmd.contains("stop") -> {
                sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                toast("Paused")
            }
            cmd.contains("next") -> {
                sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                toast("Next")
            }
            cmd.contains("back") || cmd.contains("previous") -> {
                sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                toast("Previous")
            }
            cmd.contains("louder") || cmd.contains("volume up") -> {
                repeat(3) { sendKey(KeyEvent.KEYCODE_VOLUME_UP) }
                toast("Volume up")
            }
            cmd.contains("quieter") || cmd.contains("volume down") -> {
                repeat(3) { sendKey(KeyEvent.KEYCODE_VOLUME_DOWN) }
                toast("Volume down")
            }
            cmd.contains("search") || cmd.contains("find") -> {
                val q = cmd.replace("search","").replace("find","")
                    .replace("youtube","").replace("for","").trim()
                if (q.isNotEmpty()) youtubeSearch(q)
            }
            cmd.contains("youtube") -> openYouTube()
            else -> toast("Not recognised: $cmd")
        }

        handler.postDelayed({ setStatus(if (isDriving) "Driving mode active" else "Ready") }, 2000)
    }

    // ── GESTURE (PROXIMITY SENSOR) ───────────────────────────────────────────

    override fun onSensorChanged(e: SensorEvent?) {
        e ?: return
        if (e.sensor.type != Sensor.TYPE_PROXIMITY) return
        val near = e.values[0] < e.sensor.maximumRange * 0.5f
        val now = System.currentTimeMillis()

        if (near && !isNear) {
            isNear = true
            lastNear = now
        } else if (!isNear && isNear) {
            // this branch never fires — fix below
        }

        if (!near && isNear) {
            isNear = false
            val held = now - lastNear
            if (held in 50..700) {
                // Valid wave
                waveCount++
                if (waveCount == 1) {
                    handler.postDelayed({
                        when {
                            waveCount >= 2 -> {
                                // Double wave = skip
                                val ok = SwipAccessibilityService.instance?.doSkip() ?: false
                                if (!ok) sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                                toast("Wave x2 — Skip!")
                            }
                            else -> {
                                // Single wave = play/pause
                                sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                                toast("Wave — Play/Pause")
                            }
                        }
                        waveCount = 0
                    }, 600)
                }
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ── DRIVING MODE ─────────────────────────────────────────────────────────

    private fun toggleDrive() {
        isDriving = !isDriving
        if (isDriving) {
            binding.btnDrive.text = "Driving ON"
            binding.btnDrive.setBackgroundColor(getColor(R.color.green))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setStatus("Driving mode active")
            toast("Driving mode ON — voice + gesture ready!")
        } else {
            binding.btnDrive.text = "Driving Mode"
            binding.btnDrive.setBackgroundColor(getColor(R.color.blue))
            setStatus("Ready")
            toast("Driving mode off")
        }
    }

    // ── YOUTUBE ──────────────────────────────────────────────────────────────

    private fun openYouTube() {
        try {
            val yt = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (yt != null) {
                startActivity(yt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")))
            }
        } catch (e: Exception) { toast("Cannot open YouTube") }
    }

    private fun youtubeSearch(q: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("vnd.youtube://results?search_query=${Uri.encode(q)}")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")))
        }
        toast("Searching: $q")
    }

    // ── ACCESSIBILITY ────────────────────────────────────────────────────────

    private fun refreshAccessibilityBadge() {
        val on = isAccessibilityOn()
        binding.cardAccessibility.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityOn(): Boolean {
        return try {
            val svc = "$packageName/${packageName}.service.SwipAccessibilityService"
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.contains(svc, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Enable Skip Detection")
            .setMessage(
                "Swip needs one Accessibility permission to detect YouTube\u2019s Skip Ad button.\n\n" +
                "1. Tap Enable below\n" +
                "2. Find \u201cSwip Skip Assistant\u201d in the list\n" +
                "3. Toggle it ON\n\n" +
                "This only reads YouTube\u2019s screen to find the Skip button. No personal data is accessed."
            )
            .setPositiveButton("Enable") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    private fun sendKey(keyCode: Int) {
        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {}
    }

    private fun setStatus(msg: String) {
        binding.tvStatus.text = msg
    }

    private fun setVoiceUI(active: Boolean) {
        if (active) {
            binding.btnVoice.text = "Listening..."
            binding.btnVoice.setBackgroundColor(getColor(R.color.red))
            binding.voicePulse.visibility = View.VISIBLE
        } else {
            binding.btnVoice.text = "Voice Command"
            binding.btnVoice.setBackgroundColor(getColor(R.color.purple))
            binding.btnVoice.scaleX = 1f
            binding.btnVoice.scaleY = 1f
            binding.voicePulse.visibility = View.GONE
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── LIFECYCLE ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        proxSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        refreshAccessibilityBadge()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (isListening) stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        try { unbindService(conn) } catch (_: Exception) {}
        try { unregisterReceiver(events) } catch (_: Exception) {}
    }
}
