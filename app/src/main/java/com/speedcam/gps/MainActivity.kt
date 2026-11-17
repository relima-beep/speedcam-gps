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
import androidx.camera.lifecycle.ProcessCameraProvider
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
    private lateinit var preview: PreviewView
    private lateinit var speed: TextView
    private lateinit var recordButton: Button
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraMode = 0 // 0 back, 1 front, 2 both (fallback until dual recording is implemented)
    private var filterMode = 0
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
        header.addView(speed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "⚙"
            contentDescription = "Inställningar"
            setOnClickListener { showSettings() }
        })
        root.addView(header)

        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.DKGRAY)
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
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
        val options = arrayOf("Bakkamera", "Framkamera", "Båda kamerorna (kommer senare)")
        val filters = arrayOf("Normal", "Svartvitt", "Sepia", "Kall ton")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        panel.addView(TextView(this).apply { text = "Kamera"; textSize = 18f })
        val cameraSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(cameraMode)
        }
        panel.addView(cameraSpinner)
        panel.addView(TextView(this).apply { text = "Filter (förhandsvisning)"; textSize = 18f })
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
                val requested = cameraSpinner.selectedItemPosition
                cameraMode = if (requested == 2) {
                    Toast.makeText(this, "Dubbelkamera byggs i nästa steg. Bakkameran används tills vidare.", Toast.LENGTH_LONG).show()
                    0
                } else requested
                filterMode = filterSpinner.selectedItemPosition
                prefs.edit().putInt("camera_mode", cameraMode).putInt("filter_mode", filterMode).apply()
                applyFilter()
                if (recording == null) startCameraAndGps()
                else Toast.makeText(this, "Kameravalet används efter att inspelningen stoppats", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun applyFilter() {
        // Visual preview effect only; recorded video is not filtered.
        preview.setColorFilterCompat(filterMode)
    }

    private fun PreviewView.setColorFilterCompat(mode: Int) {
        val effect = when (mode) {
            1 -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
            2 -> android.graphics.ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            3 -> android.graphics.ColorMatrix(floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            else -> null
        }
        setLayerType(if (effect == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
            effect?.let { android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(it) } })
    }

    private fun startCameraAndGps() {
        if (recording != null) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                video = VideoCapture.withOutput(Recorder.Builder().build())
                provider.unbindAll()
                val selector = if (cameraMode == 1) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(this, selector, cameraPreview, video)
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
        recording?.let { it.stop(); recordButton.isEnabled = false; return }
        val capture = video ?: run { Toast.makeText(this, "Kameran är inte redo", Toast.LENGTH_SHORT).show(); return }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "SpeedCam_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                recordButton.isEnabled = true
                recordButton.text = "● REC"
                if (event.hasError()) Toast.makeText(this, "Kunde inte spara video: ${event.error}", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Videon sparad", Toast.LENGTH_SHORT).show()
            }
        }
        recordButton.text = "■ STOPP"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        recording?.stop()
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        super.onDestroy()
    }
}
