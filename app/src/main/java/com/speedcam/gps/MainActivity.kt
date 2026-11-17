package com.speedcam.gps

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import android.provider.MediaStore
import android.content.ContentValues

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var speed: TextView
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val locationListener = LocationListener { loc -> speed.text = "%.0f km/h".format(if (loc.hasSpeed()) loc.speed * 3.6 else 0.0) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.all { ok -> ok }) startCameraAndGps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speed = TextView(this).apply { text = "-- km/h"; textSize = 30f; setPadding(20,20,20,20) }
        preview = PreviewView(this)
        val button = Button(this).apply { text = "Start recording"; setOnClickListener { toggleRecording(this) } }
        layout.addView(speed)
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(button)
        setContentView(layout)
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startCameraAndGps() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            video = VideoCapture.withOutput(Recorder.Builder().build())
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, video)
        }, ContextCompat.getMainExecutor(this))
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener) } catch (_: Exception) { }
        }
    }

    private fun toggleRecording(button: Button) {
        recording?.let { it.stop(); recording = null; button.text = "Start recording"; return }
        val capture = video ?: return
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "SpeedCam_${System.currentTimeMillis()}.mp4"); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4") }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) { recording = null; button.text = "Start recording" }
        }
        button.text = "Stop recording"
    }
    override fun onDestroy() { recording?.stop(); (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener); super.onDestroy() }
}
