package com.app.videoeditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.camera.CameraAspectRatio
import com.app.videoeditor.editor.EditorActivity
import com.app.videoeditor.gl.LutCameraEffect
import com.app.videoeditor.gl.LutSurfaceProcessor
import com.app.videoeditor.lut.LutData
import com.app.videoeditor.lut.LutLoader
import com.app.videoeditor.lut.LutSoftwareApplier
import com.app.videoeditor.lut.UnsupportedLutFormatException
import com.app.videoeditor.ui.FilterAdapter
import com.app.videoeditor.ui.FilterItem
import com.app.videoeditor.widget.CameraCaptureButton
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var previewContainer: View
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: CameraCaptureButton
    private lateinit var timerText: TextView
    private lateinit var zoomRail: View
    private lateinit var zoomFill: View
    private lateinit var zoomLabel: TextView
    private lateinit var ratioButton: TextView
    private lateinit var ratioPicker: View
    private lateinit var filterStrip: RecyclerView
    private lateinit var filterNameLabel: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var currentAspectRatio = CameraAspectRatio.default()
    private lateinit var lutProcessor: LutSurfaceProcessor
    private lateinit var cameraEffect: CameraEffect
    private var currentLutForPhoto: LutData? = null

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cameraOk = grants[Manifest.permission.CAMERA] == true
            val micOk = grants[Manifest.permission.RECORD_AUDIO] == true
            if (cameraOk && micOk) startCamera() else {
                Toast.makeText(this, "Camera and microphone permissions are needed", Toast.LENGTH_LONG).show()
            }
        }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            openEditor(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewContainer = findViewById(R.id.previewContainer)
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        timerText = findViewById(R.id.timerText)
        zoomRail = findViewById(R.id.zoomRail)
        zoomFill = findViewById(R.id.zoomFill)
        zoomLabel = findViewById(R.id.zoomLabel)
        ratioButton = findViewById(R.id.ratioButton)
        ratioPicker = findViewById(R.id.ratioPicker)
        filterStrip = findViewById(R.id.filterStrip)
        filterNameLabel = findViewById(R.id.filterNameLabel)
        lutProcessor = LutSurfaceProcessor()

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        cameraEffect = LutCameraEffect(
            lutProcessor,
            ContextCompat.getMainExecutor(this),
            Consumer { e -> Log.e("MainActivity", "Filter pipeline error", e) }
        )

        setupCaptureButton()
        setupRatioPicker()
        setupFilterStrip()
        findViewById<View>(R.id.editVideoButton).setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }
        ensurePermissionsThenStartCamera()
    }

    private fun openEditor(uri: Uri) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_VIDEO_URI, uri)
        )
    }

    // ===================== Capture button =====================

    private fun setupCaptureButton() {
        captureButton.listener = object : CameraCaptureButton.Listener {
            override fun onCapturePhoto() = takePhoto()

            override fun onRecordStart() {
                zoomRail.visibility = View.VISIBLE
                timerText.visibility = View.VISIBLE
                timerText.text = "0:00"
                startRecording()
            }

            override fun onRecordProgress(fraction: Float) {
                // Real timer text is driven by VideoRecordEvent.Status below - more accurate.
            }

            override fun onZoomChange(zoomFactor: Float) {
                camera?.cameraControl?.setZoomRatio(zoomFactor)
                zoomLabel.text = String.format(Locale.US, "%.1fx", zoomFactor)
                val range = captureButton.maxZoomFactor - captureButton.minZoomFactor
                val fraction = if (range > 0f) (zoomFactor - captureButton.minZoomFactor) / range else 0f
                val track = zoomFill.parent as View
                val params = zoomFill.layoutParams
                params.height = (track.height * fraction).toInt()
                zoomFill.layoutParams = params
            }

            override fun onRecordStop(durationMs: Long) {
                zoomRail.visibility = View.GONE
                stopRecording()
            }
        }
    }

    // ===================== Aspect ratio picker =====================

    private fun setupRatioPicker() {
        ratioButton.setOnClickListener {
            ratioPicker.visibility = if (ratioPicker.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.ratioChip_1_1).setOnClickListener { selectRatio(CameraAspectRatio.SQUARE) }
        findViewById<View>(R.id.ratioChip_4_5).setOnClickListener { selectRatio(CameraAspectRatio.PORTRAIT_4_5) }
        findViewById<View>(R.id.ratioChip_16_9).setOnClickListener { selectRatio(CameraAspectRatio.FULL_9_16) }
    }

    private fun selectRatio(ratio: CameraAspectRatio) {
        currentAspectRatio = ratio
        ratioButton.text = ratio.label
        ratioPicker.visibility = View.GONE

        val params = previewContainer.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = "${ratio.rational.numerator}:${ratio.rational.denominator}"
        previewContainer.layoutParams = params

        bindUseCases() // rebind so ViewPort crops to the new ratio too
    }

    // ===================== Filter strip =====================

    private fun setupFilterStrip() {
        // Dynamically enumerate assets/luts/ so ANY LUT file you drop in (or import) shows
        // up automatically - no need to hardcode each one here.
        val lutFiles = try {
            assets.list("luts")?.filter {
                it.endsWith(".cube", ignoreCase = true) ||
                    it.endsWith(".3dl", ignoreCase = true) ||
                    it.endsWith(".png", ignoreCase = true)
            }?.sorted()
        } catch (e: Exception) {
            null
        } ?: emptyList()

        val filters = buildList {
            add(FilterItem("normal", "Normal", null, android.R.drawable.ic_menu_camera))
            lutFiles.forEach { file ->
                val name = file.substringBeforeLast('.').replaceFirstChar { it.uppercaseChar() }
                add(FilterItem("lut_$file", name, "luts/$file", android.R.drawable.ic_menu_gallery))
            }
        }

        val adapter = FilterAdapter(filters) { item -> applyFilter(item) }
        filterStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        filterStrip.adapter = adapter

        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(filterStrip)
        filterStrip.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val layoutManager = rv.layoutManager ?: return
                val snapView = snapHelper.findSnapView(layoutManager) ?: return
                val position = layoutManager.getPosition(snapView)
                adapter.setSelectedPosition(position)
            }
        })
    }

    private fun applyFilter(item: FilterItem) {
        filterNameLabel.text = item.displayName

        if (item.lutAssetPath == null) {
            currentLutForPhoto = null
            lutProcessor.setLut(null)
            return
        }

        bgExecutor.execute {
            try {
                val lut = LutLoader.loadFromAssets(this, item.lutAssetPath)
                currentLutForPhoto = lut
                lutProcessor.setLut(lut)
            } catch (e: UnsupportedLutFormatException) {
                runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Couldn't load ${item.displayName}: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ===================== Camera setup =====================

    private fun ensurePermissionsThenStartCamera() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startCamera() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder().build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val viewPort = ViewPort.Builder(currentAspectRatio.rational, rotation).build()

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageCapture!!)
            .addUseCase(videoCapture!!)
            .addEffect(cameraEffect)
            .build()

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
            camera?.cameraInfo?.zoomState?.value?.let { zoomState ->
                captureButton.setZoomRange(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== Photo capture (filter baked in via software LUT pass) =====================

    private fun takePhoto() {
        val ic = imageCapture ?: return
        ic.takePicture(bgExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                val finalBitmap = currentLutForPhoto?.let { LutSoftwareApplier.apply(bitmap, it) } ?: bitmap
                saveBitmapToMediaStore(finalBitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Photo failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = AndroidMatrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap) {
        val name = "IMG_" + timestamp()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VideoEditorApp")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            runOnUiThread { Toast.makeText(this, "Couldn't save photo", Toast.LENGTH_SHORT).show() }
            return
        }
        contentResolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        runOnUiThread { Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show() }
    }

    // ===================== Video recording (filter is already baked in by the GL effect) =====================

    @SuppressLint("MissingPermission") // both permissions are confirmed before recording is reachable
    private fun startRecording() {
        val vc = videoCapture ?: return
        val name = "VID_" + timestamp()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditorApp")
            }
        }
        val outputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        activeRecording = vc.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        val seconds = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                        timerText.text = formatTimer(seconds)
                    }
                    is VideoRecordEvent.Finalize -> {
                        val seconds = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                        timerText.visibility = View.GONE
                        timerText.text = "0:00"
                        if (!event.hasError()) {
                            Toast.makeText(this, "Video saved • ${formatTimer(seconds)}", Toast.LENGTH_SHORT).show()
                            openEditor(event.outputResults.outputUri)
                        } else {
                            Toast.makeText(this, "Recording failed: ${event.error}", Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> Unit
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun formatTimer(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        lutProcessor.release()
        bgExecutor.shutdown()
    }
}