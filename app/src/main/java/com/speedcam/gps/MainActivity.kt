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
