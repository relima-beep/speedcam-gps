package com.speedcam.gps

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import android.util.Rational
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

/** MVP: rear-camera video + microphone + live GPS HUD. Does not request audio focus. */
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var selfiePreview: PreviewView
    private lateinit var cameraModeButton: Button
    private var frontCapture: VideoCapture<Recorder>? = null
    private var frontRecording: Recording? = null
    private var dualEnabled = true
    private var concurrentSupported = false
    private lateinit var speedText: TextView
    private lateinit var effectStudio: EffectStudio
    private lateinit var effectTint: View
    private lateinit var frameText: TextView
    private lateinit var stickerText: TextView
    private var currentKmh = 0.0
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationSamples = mutableListOf<String>()
    private var lastVideoUri: android.net.Uri? = null
    private var startElapsed = 0L
    private var lastPoint: android.location.Location? = null
    private var distanceMeters = 0.0
    private var recordingDual = false
    private lateinit var challengesText: TextView
    private val challengeEngine by lazy { ChallengeEngine(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val kmh = if (loc.hasSpeed()) (loc.speed * 3.6).coerceAtLeast(0.0) else 0.0
            currentKmh = kmh
            updateHud(effectStudio.style)
            if (recording != null) {
                val previous = lastPoint
                if (previous != null && loc.accuracy <= 35f && previous.accuracy <= 35f &&
                    loc.time > previous.time && loc.time - previous.time <= 10000L &&
                    loc.distanceTo(previous) < 100f && kmh < 130.0) {
                    distanceMeters += loc.distanceTo(previous)
                }
                lastPoint = loc
                val elapsed = SystemClock.elapsedRealtime() - startElapsed
                locationSamples.add("$elapsed,${loc.time},${loc.latitude},${loc.longitude},${String.format(Locale.US,"%.2f",kmh)},${loc.accuracy}")
            }
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) bindCamera()
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        effectStudio = EffectStudio(this) { updateHud(it) }
        previewView = PreviewView(this)
        selfiePreview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        speedText = TextView(this).apply { text = "-- km/h"; textSize = 34f; setTextColor(0xffffffff.toInt()); setBackgroundColor(0x99000000.toInt()); setPadding(24, 12, 24, 12) }
        statusText = TextView(this).apply { text = "Redo – musikappar lämnas ostörda"; setTextColor(0xffffffff.toInt()); setBackgroundColor(0x99000000.toInt()); setPadding(12, 8, 12, 8) }
        recordButton = Button(this).apply { text = "Starta inspelning"; setOnClickListener { if (recording == null) startRecording() else stopRecording() } }
        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        effectTint = View(this).apply { isClickable = false }
        root.addView(effectTint, FrameLayout.LayoutParams(-1, -1))
        frameText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(18, 8, 18, 8) }
        root.addView(frameText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = 50 })
        stickerText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 24f; setPadding(18, 8, 18, 8) }
        root.addView(stickerText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        root.addView(selfiePreview, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels / 3, resources.displayMetrics.heightPixels / 4, Gravity.TOP or Gravity.END).apply { topMargin = 150; rightMargin = 18 })
        cameraModeButton = Button(this).apply { text = "Dubbelkamera: auto"; setOnClickListener { if (recording == null) { dualEnabled = !dualEnabled; bindCamera() } } }
        root.addView(speedText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 50 })
        challengesText = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); setPadding(12, 8, 12, 8)
            text = "Utmaningar visas efter inspelningen"
        }
        val challengesButton = Button(this).apply { text = "Utmaningar & XP"; setOnClickListener { showChallenges() } }
        val studioButton = Button(this).apply { text = "Effektstudio"; setOnClickListener { effectStudio.open() } }
        val profilesButton = Button(this).apply { text = "Mina profiler"; setOnClickListener { effectStudio.showProfiles() } }
        val shareButton = Button(this).apply { text = "Dela senaste video"; setOnClickListener { shareLatestVideo() } }
        val liveButton = Button(this).apply { text = "Live • inställningar (beta)"; setOnClickListener { showLiveSettings() } }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(statusText); addView(challengesText); addView(challengesButton); addView(cameraModeButton); addView(studioButton); addView(profilesButton); addView(shareButton); addView(liveButton); addView(recordButton) }
        root.addView(bottom, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 50 })
        setContentView(root)
        updateHud(effectStudio.style)
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** Live UI decoration only. Exported MP4 is not composited in v5. */
    private fun updateHud(style: EffectStyle) {
        if (!::speedText.isInitialized || !::effectTint.isInitialized) return
        val tint = when (style.filter) {
            "Cinematic" -> 0x263E7997
            "Svartvitt" -> 0x55777777 // tint preview, not true grayscale
            "Neon Night" -> 0x443A12B8
            "Retro VHS" -> 0x448A6735
            "Varm" -> 0x33FF8800
            "Kall" -> 0x3355BBFF
            else -> Color.TRANSPARENT
        }
        effectTint.setBackgroundColor(tint)
        frameText.text = when (style.frame) {
            "Racing" -> "▌ RACING CAM ▐"
            "Neon" -> "✦ NEON RIDER ✦"
            "Retro" -> "● REC  VHS"
            "Minimal" -> "GPS CAM"
            else -> ""
        }
        frameText.setBackgroundColor(when (style.frame) {
            "Racing" -> 0xBB8B0000.toInt()
            "Neon" -> 0xBB4B0082.toInt()
            "Retro" -> 0xAA3D342A.toInt()
            else -> Color.TRANSPARENT
        })
        stickerText.text = when (style.sticker) {
            "REC" -> "● REC"
            "Stjärnor" -> "★ ★"
            "Blixt" -> "⚡"
            "Trofé" -> "🏆"
            else -> ""
        }
        speedText.visibility = if (style.speedometer == "Dold") View.GONE else View.VISIBLE
        speedText.text = when (style.speedometer) {
            "Analog" -> String.format(Locale.getDefault(), "◉ %.0f km/h", currentKmh)
            "Båda" -> String.format(Locale.getDefault(), "◉  %.0f km/h", currentKmh)
            else -> String.format(Locale.getDefault(), "%.0f km/h", currentKmh)
        }
        speedText.setTypeface(null, Typeface.BOLD)
    }

    private fun makeVideoCapture(cameraInfo: androidx.camera.core.CameraInfo): Pair<Preview, VideoCapture<Recorder>> {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val modes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        ) ?: intArrayOf()
        val previewBuilder = Preview.Builder()
        val videoBuilder = VideoCapture.Builder(Recorder.Builder().build())
        if (modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }
        return previewBuilder.build() to videoBuilder.build()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (recording != null) return@addListener
                provider.unbindAll()
                val rearInfo = provider.availableCameraInfos.firstOrNull {
                    CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty()
                } ?: throw IllegalStateException("Bakre kamera saknas")
                val (rearPreview, rearVideo) = makeVideoCapture(rearInfo)
                rearPreview.surfaceProvider = previewView.surfaceProvider
                videoCapture = rearVideo
                frontCapture = null
                selfiePreview.visibility = android.view.View.GONE
                concurrentSupported = provider.availableConcurrentCameraInfos.any { cameraList ->
                    cameraList.any { CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty() } &&
                    cameraList.any { CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(it)).isNotEmpty() }
                }
                if (dualEnabled && concurrentSupported) {
                    try {
                        val frontInfo = provider.availableCameraInfos.first {
                            CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(it)).isNotEmpty()
                        }
                        val (frontPreview, frontVideo) = makeVideoCapture(frontInfo)
                        frontPreview.surfaceProvider = selfiePreview.surfaceProvider
                        val rearConfig = ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            UseCaseGroup.Builder().addUseCase(rearPreview).addUseCase(rearVideo).build(),
                            this
                        )
                        val frontConfig = ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            UseCaseGroup.Builder().addUseCase(frontPreview).addUseCase(frontVideo).build(),
                            this
                        )
                        provider.bindToLifecycle(listOf(rearConfig, frontConfig))
                        frontCapture = frontVideo
                        selfiePreview.visibility = android.view.View.VISIBLE
                        statusText.text = "Dubbelkamera redo • GPS • antiskak auto"
                    } catch (dualError: Exception) {
                        provider.unbindAll()
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, rearPreview, rearVideo)
                        statusText.text = "Enkelkamera: dubbelvideo stöds inte i denna konfiguration"
                    }
                } else {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, rearPreview, rearVideo)
                    statusText.text = if (dualEnabled) "Enkelkamera: telefonen saknar samtidig fram/bak-kamera" else "Enkelkamera vald"
                }
                cameraModeButton.text = if (frontCapture != null) "Dubbelkamera: PÅ" else "Dubbelkamera: AV / ej stödd"
            } catch (e: Exception) { statusText.text = "Kamerafel: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
        locationClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "SpeedCam_${System.currentTimeMillis()}.mp4"); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedCamGPS") }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        locationSamples.clear()
        distanceMeters = 0.0
        lastPoint = null
        recordingDual = frontCapture != null
        challengeEngine.startSession()
        challengesText.text = challengeEngine.activeDescription()
        locationSamples.add("elapsed_ms,utc_ms,latitude,longitude,speed_kmh,accuracy_m")
        startElapsed = SystemClock.elapsedRealtime()
        // Separate front MP4 is used instead of falsely claiming a composited picture-in-picture MP4.
        frontCapture?.let { front ->
            val frontValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SpeedCam_SELFIE_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SpeedCamGPS")
            }
            val frontOptions = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(frontValues).build()
            try {
                frontRecording = front.output.prepareRecording(this, frontOptions)
                    .start(ContextCompat.getMainExecutor(this)) { frontEvent ->
                        if (frontEvent is VideoRecordEvent.Finalize) {
                            frontRecording = null
                            if (frontEvent.hasError()) statusText.text = "Selfievideo misslyckades: ${frontEvent.error}"
                        }
                    }
            } catch (e: Exception) { statusText.text = "Selfie kunde inte startas: ${e.message}" }
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { recordButton.text = "Stoppa inspelning"; statusText.text = "REC • mikrofon aktiv • musik kan fortsätta" }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recordButton.text = "Starta inspelning"
                    if (!event.hasError()) lastVideoUri = event.outputResults.outputUri
                    statusText.text = if (event.hasError()) "Inspelningsfel: ${event.error}" else "Video sparad i Movies/SpeedCamGPS"
                    if (!event.hasError()) {
                        saveTelemetry()
                        val seconds = ((SystemClock.elapsedRealtime() - startElapsed) / 1000L).coerceAtLeast(0)
                        val report = challengeEngine.finishSession(distanceMeters, seconds, recordingDual)
                        challengesText.text = report
                        AlertDialog.Builder(this).setTitle("Tur avslutad • XP")
                            .setMessage("Sträcka: %.2f km\nTid: %d min\n\n%s".format(Locale.getDefault(), distanceMeters / 1000.0, seconds / 60, report))
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }
    }
    /** Uses Android Sharesheet: the receiving social app controls whether it accepts/uploads the file. */
    private fun shareLatestVideo() {
        val uri = lastVideoUri
        if (uri == null) {
            Toast.makeText(this, "Spela in och spara en video först", Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Dela video till…"))
    }

    /** Stores destination configuration only. RTMP encoding and transport are NOT implemented yet. */
    private fun showLiveSettings() {
        val prefs = getSharedPreferences("live_settings", MODE_PRIVATE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 8) }
        val platform = android.widget.Spinner(this)
        val platforms = arrayOf("Facebook", "TikTok", "Instagram", "Annan RTMPS")
        platform.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, platforms)
        platform.setSelection(platforms.indexOf(prefs.getString("platform", "Facebook")).coerceAtLeast(0))
        val url = EditText(this).apply { hint = "RTMPS-serveradress"; setSingleLine(true); setText(prefs.getString("url", "")) }
        val key = EditText(this).apply {
            hint = "Streamnyckel (anges vid sändning)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(TextView(this).apply { text = "Plattform (kräver egen åtkomst till live-streamnyckel)" })
        layout.addView(platform)
        layout.addView(url)
        layout.addView(key)
        layout.addView(TextView(this).apply { text = "OBS: Här sparas bara serveradressen och plattformen. Live-sändning är inte implementerad. Streamnyckeln sparas inte." })
        AlertDialog.Builder(this).setTitle("Livestreaming – förberedelser")
            .setView(layout)
            .setNegativeButton("Avbryt", null)
            .setPositiveButton("Spara inställningar") { _, _ ->
                prefs.edit().putString("platform", platforms[platform.selectedItemPosition])
                    .putString("url", url.text.toString().trim()).apply()
                key.text.clear()
                Toast.makeText(this, "Inställningar sparade. Livesändning kommer i senare version.", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun showChallenges() {
        AlertDialog.Builder(this).setTitle("Utmaningar & XP")
            .setMessage("Totalt: ${challengeEngine.totalXp()} XP\n\n${challengeEngine.activeDescription()}\n\nMål kontrolleras efter avslutad inspelning. Inga hastighetsutmaningar.")
            .setPositiveButton("OK", null).show()
    }

    private fun stopRecording() { recording?.stop(); frontRecording?.stop() }
    private fun saveTelemetry() {
        // GPS sidecar CSV; not burned into video in this MVP.
        try {
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "SpeedCam_GPS_${System.currentTimeMillis()}.csv"); put(MediaStore.Downloads.MIME_TYPE, "text/csv"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/SpeedCamGPS") }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(locationSamples.joinToString("\n")) }
        } catch (e: Exception) { statusText.text = "Video sparad; GPS-logg kunde inte sparas" }
    }
    override fun onDestroy() { recording?.stop(); frontRecording?.stop(); locationClient.removeLocationUpdates(callback); super.onDestroy() }
}
