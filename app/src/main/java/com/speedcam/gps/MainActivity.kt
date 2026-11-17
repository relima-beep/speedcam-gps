package com.speedcam.gps

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.ConcurrentCamera
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var backPreview: PreviewView
    private lateinit var frontPreview: PreviewView
    private lateinit var speed: TextView
    private lateinit var recordButton: Button
    private lateinit var cameraFrame: FrameLayout
    private var backVideo: VideoCapture<Recorder>? = null
    private var frontVideo: VideoCapture<Recorder>? = null
    private var backRecording: Recording? = null
    private var frontRecording: Recording? = null
    private var activeRecordings = 0
    private var cameraMode = 0
    private var filterMode = 0
    private var dualActive = false
    private val speedOverlay = SpeedOverlay()
    private val prefs by lazy { getSharedPreferences("speedcam_settings", MODE_PRIVATE) }
    private val locationListener = LocationListener { loc ->
        speed.text = "%.0f km/h".format(if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCameraAndGps()
        else Toast.makeText(this, "Kamerabehörighet krävs", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraMode = prefs.getInt("camera_mode", 0)
        filterMode = prefs.getInt("filter_mode", 0)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        speed = TextView(this).apply {
            text = "-- km/h"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        header.addView(speed, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "⚙"
            contentDescription = "Inställningar"
            setOnClickListener { showSettings() }
        })
        root.addView(header)
        cameraFrame = FrameLayout(this)
        backPreview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        frontPreview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        cameraFrame.addView(backPreview, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(frontPreview, FrameLayout.LayoutParams(dp(135), dp(185), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(12), dp(12), dp(8))
        })
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        recordButton = Button(this).apply {
            text = "● REC"
            textSize = 19f
            setOnClickListener { toggleRecording() }
        }
        controls.addView(recordButton, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(controls)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        applyFilter()
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun showSettings() {
        val cameras = arrayOf("Bakkamera", "Framkamera", "Båda (PiP + två filer)")
        val filters = arrayOf("Normal", "Svartvitt", "Sepia", "Kall ton")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        panel.addView(TextView(this).apply { text = "Kamera"; textSize = 18f })
        val cameraSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, cameras)
            setSelection(cameraMode)
        }
        panel.addView(cameraSpinner)
        panel.addView(TextView(this).apply { text = "Filter (endast förhandsvisning)"; textSize = 18f })
        val filterSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, filters)
            setSelection(filterMode)
        }
        panel.addView(filterSpinner)
        android.app.AlertDialog.Builder(this)
            .setTitle("SpeedCam-inställningar")
            .setView(panel)
            .setNegativeButton("Avbryt", null)
            .setPositiveButton("Spara") { _, _ ->
                if (activeRecordings > 0) {
                    Toast.makeText(this, "Stoppa inspelningen innan kamerabyte", Toast.LENGTH_LONG).show()
                } else {
                    cameraMode = cameraSpinner.selectedItemPosition
                    filterMode = filterSpinner.selectedItemPosition
                    prefs.edit().putInt("camera_mode", cameraMode).putInt("filter_mode", filterMode).apply()
                    applyFilter()
                    startCameraAndGps()
                }
            }.show()
    }

    private fun applyFilter() {
        listOf(backPreview, frontPreview).forEach { view ->
            val matrix = when (filterMode) {
                1 -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
                2 -> android.graphics.ColorMatrix(floatArrayOf(
                    .393f,.769f,.189f,0f,0f, .349f,.686f,.168f,0f,0f,
                    .272f,.534f,.131f,0f,0f, 0f,0f,0f,1f,0f))
                3 -> android.graphics.ColorMatrix(floatArrayOf(
                    .9f,0f,0f,0f,0f, 0f,1f,0f,0f,0f,
                    0f,0f,1.2f,0f,0f, 0f,0f,0f,1f,0f))
                else -> null
            }
            view.setLayerType(if (matrix == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
                matrix?.let { android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(it)
                } })
        }
    }

    private fun startCameraAndGps() {
        if (activeRecordings > 0) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                backVideo = null
                frontVideo = null
                dualActive = false
                (frontPreview.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dp(135); height = dp(185); gravity = Gravity.TOP or Gravity.END
                    setMargins(dp(8), dp(12), dp(12), dp(8))
                    frontPreview.layoutParams = this
                }
                frontPreview.visibility = View.GONE
                backPreview.visibility = View.VISIBLE
                if (cameraMode == 2 && provider.availableConcurrentCameraInfos.any { pair ->
                    pair.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    pair.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                }) {
                    val back = VideoCapture.withOutput(Recorder.Builder().build())
                    val front = VideoCapture.withOutput(Recorder.Builder().build())
                    val backCase = Preview.Builder().build().also { it.surfaceProvider = backPreview.surfaceProvider }
                    val frontCase = Preview.Builder().build().also { it.surfaceProvider = frontPreview.surfaceProvider }
                    val backGroup = UseCaseGroup.Builder().addUseCase(backCase).addUseCase(back).build()
                    val frontGroup = UseCaseGroup.Builder().addUseCase(frontCase).addUseCase(front).build()
                    provider.bindToLifecycle(listOf(
                        ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, backGroup, this),
                        ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, this)
                    ))
                    backVideo = back
                    frontVideo = front
                    dualActive = true
                    frontPreview.visibility = View.VISIBLE
                } else {
                    if (cameraMode == 2) Toast.makeText(this,
                        "Dubbelkamera stöds inte av enheten eller kamerakombinationen. Bakkamera används.",
                        Toast.LENGTH_LONG).show()
                    val capture = VideoCapture.withOutput(Recorder.Builder().build())
                    val selected = if (cameraMode == 1) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val view = if (cameraMode == 1) frontPreview else backPreview
                    if (cameraMode == 1) {
                        backPreview.visibility = View.GONE
                        frontPreview.visibility = View.VISIBLE
                        (frontPreview.layoutParams as FrameLayout.LayoutParams).apply {
                            width = -1; height = -1; gravity = Gravity.FILL
                            setMargins(0,0,0,0)
                            frontPreview.layoutParams = this
                        }
                    }
                    val previewCase = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    provider.bindToLifecycle(this, selected, previewCase, capture)
                    if (cameraMode == 1) frontVideo = capture else backVideo = capture
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Kamerafel: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { lm.removeUpdates(locationListener); lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener) }
            catch (_: Exception) { }
        }
    }

    private fun toggleRecording() {
        if (activeRecordings > 0) {
            backRecording?.stop()
            frontRecording?.stop()
            recordButton.isEnabled = false
            return
        }
        val targets = listOfNotNull(
            backVideo?.let { "Back" to it },
            frontVideo?.let { "Front" to it }
        )
        if (targets.isEmpty()) {
            Toast.makeText(this, "Kameran är inte redo", Toast.LENGTH_SHORT).show()
            return
        }
        activeRecordings = targets.size
        recordButton.text = "■ STOPP"
        val stamp = System.currentTimeMillis()
        targets.forEach { (label, capture) ->
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SpeedCam_${stamp}_${label}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
            val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build()
            var pending = capture.output.prepareRecording(this, output)
            // Audio only on the primary camera, to avoid microphone conflicts.
            if (label == "Back" && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            val recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    activeRecordings = (activeRecordings - 1).coerceAtLeast(0)
                    if (label == "Back") backRecording = null else frontRecording = null
                    if (event.hasError()) Toast.makeText(this, "$label: sparfel ${event.error}", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "$label-video sparad", Toast.LENGTH_SHORT).show()
                    if (activeRecordings == 0) {
                        recordButton.isEnabled = true
                        recordButton.text = "● REC"
                    }
                }
            }
            if (label == "Back") backRecording = recording else frontRecording = recording
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        backRecording?.stop()
        frontRecording?.stop()
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        super.onDestroy()
    }
}
