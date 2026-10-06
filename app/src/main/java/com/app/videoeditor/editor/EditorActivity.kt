package com.app.videoeditor.editor

import android.annotation.SuppressLint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.app.videoeditor.core.EditorState
import com.app.videoeditor.core.HistoryManager
import com.app.videoeditor.core.OverlayManager
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.videoeditor.core.EditorDrawing
import com.app.videoeditor.widget.BrushType
import com.app.videoeditor.widget.DrawCanvasView

class EditorActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var stickerOverlay: android.widget.FrameLayout
    private lateinit var btnUndo: TextView
    private lateinit var btnRedo: TextView

    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var isExporting = false

    private lateinit var overlayManager: OverlayManager
    private val historyManager = HistoryManager<EditorState>()
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Provider ne persistable permission nahi di -- is session ke liye
                // Uri phir bhi kaam karegi, bas app restart ke baad access nahi rahega.
            }
            overlayManager.addSticker(imageUri = it.toString())
        }
    }
    private var musicPlayer: ExoPlayer? = null
    private var selectedMusicUri: Uri? = null
    private var isOriginalAudioMuted = false
    private lateinit var drawCanvasView: DrawCanvasView
    private var isDrawModeActive = false
    private var drawToolbarView: View? = null

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_editor)

        setupInsets()
        initViews()

        videoUri = intent.getParcelableExtra(EXTRA_VIDEO_URI)
        if (videoUri == null) {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPlayer(videoUri!!)
        setupOverlayManager()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        stickerOverlay = findViewById(R.id.stickerOverlay)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        setupFeaturesRv()

        btnUndo.setOnClickListener {
            historyManager.undo()?.let { state -> overlayManager.restoreState(state); updateUndoRedoButtons() }
        }
        btnRedo.setOnClickListener {
            historyManager.redo()?.let { state -> overlayManager.restoreState(state); updateUndoRedoButtons() }
        }

        stickerOverlay.setOnClickListener {
            overlayManager.deselectAll()
        }
    }

    private fun setupFeaturesRv() {
        val features = listOf(
            FeatureItem("Text", R.drawable.ic_text),
            FeatureItem("Sticker", R.drawable.ic_sticker_add),
            FeatureItem("Music", R.drawable.ic_music_note_2_24dp_e3e3e3_fill0_wght200_grad0_opsz24),
            FeatureItem("Mute", android.R.drawable.ic_lock_silent_mode),
            FeatureItem("Filter", R.drawable.ic_filter),
            FeatureItem("Draw", R.drawable.ic_draw),
            FeatureItem("Download", R.drawable.ic_download)
        )
        val rv = findViewById<RecyclerView>(R.id.rvFeatures)
        rv.setHasFixedSize(true)
        rv.adapter = FeatureAdapter(features) { item ->
            when (item.title) {
                "Text" -> showAddTextDialog()
                "Sticker" -> showStickerSheet()
                "Music" -> showMusicSheet()
                "Mute" -> toggleMuteOriginalAudio()
                "Draw" -> toggleDrawMode()
                "Download" -> exportVideo()
                else -> Toast.makeText(this, "${item.title} - coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddTextDialog() {
        // Dialog ke duran editor screen me sirf video + overlay dikhe, baaki
        // UI (top bar/features) invisible rahe. Dismiss ho jaane par wapas.
        findViewById<View>(R.id.topBar).visibility = View.INVISIBLE
        findViewById<View>(R.id.rvFeatures).visibility = View.INVISIBLE
        AddTextDialog(this, overlayManager) {
            findViewById<View>(R.id.topBar).visibility = View.VISIBLE
            findViewById<View>(R.id.rvFeatures).visibility = View.VISIBLE
        }.show()
    }

    private fun showStickerSheet() {
        StickerBottomSheet(
            context = this,
            items = StickerCatalog.all(this),
            onEmojiPicked = { emoji -> overlayManager.addSticker(emoji = emoji) },
            onImagePicked = { uri -> overlayManager.addSticker(imageUri = uri.toString()) },
            onPickFromGallery = { pickImageLauncher.launch("image/*") }
        ).show()
    }

    private fun showMusicSheet() {
        val wasPlaying = musicPlayer?.isPlaying == true
        musicPlayer?.pause()

        MusicBottomSheet(
            context = this,
            songs = MusicCatalog.all(this),
            onPicked = { song ->
                if (song == null) {
                    selectedMusicUri = null
                    setupMusicPreview()
                    Toast.makeText(this, "Music removed", Toast.LENGTH_SHORT).show()
                } else {
                    AudioTrimDialog(
                        activity = this,
                        audioUri = song.uri,
                        onTrimmed = { trimmedFile ->
                            selectedMusicUri = Uri.fromFile(trimmedFile)
                            setupMusicPreview()
                            Toast.makeText(this, "${song.title} added", Toast.LENGTH_SHORT).show()
                        },
                        onCancelled = {
                            if (wasPlaying) musicPlayer?.play()
                        }
                    ).show()
                }
            },
            onCancelled = {
                // FIX: bottom sheet khud hi band ho gayi, bina kuch pick kiye --
                // purana music wapas resume karo.
                if (wasPlaying) musicPlayer?.play()
            }
        ).show()
    }

    private fun toggleMuteOriginalAudio() {
        isOriginalAudioMuted = !isOriginalAudioMuted
        player?.volume = if (isOriginalAudioMuted) 0f else 1f
        Toast.makeText(this, if (isOriginalAudioMuted) "Original audio muted" else "Original audio unmuted", Toast.LENGTH_SHORT).show()
    }

    // NOTE: yeh sirf PREVIEW ke liye hai -- music video ke saath best-effort
    // loop hoke bajegi (perfectly frame-sync nahi hai), lekin asli mixing
    // export ke time FFmpeg karta hai (VideoExporter me).
    private fun setupMusicPreview() {
        musicPlayer?.release()
        musicPlayer = null
        val uri = selectedMusicUri ?: return
        musicPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    private fun setupOverlayManager() {
        overlayManager = OverlayManager(this, stickerOverlay) {
            historyManager.push(overlayManager.getCurrentState())
            updateUndoRedoButtons()
        }
        drawCanvasView = overlayManager.setupDrawing()
        historyManager.push(overlayManager.getCurrentState())
        updateUndoRedoButtons()
    }

    private fun updateUndoRedoButtons() {
        btnUndo.alpha = if (historyManager.canUndo) 1.0f else 0.4f
        btnRedo.alpha = if (historyManager.canRedo) 1.0f else 0.4f
        btnUndo.isEnabled = historyManager.canUndo
        btnRedo.isEnabled = historyManager.canRedo
    }

    private fun setupPlayer(uri: Uri) {
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
        playerView.player = player
    }

    private fun toggleDrawMode() {
        if (isDrawModeActive) exitDrawMode() else enterDrawMode()
    }

    private fun enterDrawMode() {
        isDrawModeActive = true
        drawCanvasView.isDrawingEnabled = true
        drawCanvasView.bringToFront() // taaki draw-mode me touches sticker/text ke neeche na chali jaayein
        overlayManager.deselectAll()
        showDrawToolbar()
    }

    private fun exitDrawMode() {
        isDrawModeActive = false
        drawCanvasView.isDrawingEnabled = false
        hideDrawToolbar()
    }

    private fun showDrawToolbar() {
        findViewById<View>(R.id.topBar).visibility = View.INVISIBLE
        findViewById<View>(R.id.rvFeatures).visibility = View.INVISIBLE

        if (drawToolbarView != null) { drawToolbarView?.visibility = View.VISIBLE; return }

        val editorRoot = findViewById<ConstraintLayout>(R.id.editorRoot)
        val toolbar = layoutInflater.inflate(R.layout.draw_toolbar, editorRoot, false)
        val lp = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        toolbar.layoutParams = lp
        editorRoot.addView(toolbar)
        drawToolbarView = toolbar
        wireDrawToolbar(toolbar)
    }

    private fun hideDrawToolbar() {
        drawToolbarView?.visibility = View.GONE
        findViewById<View>(R.id.topBar).visibility = View.VISIBLE
        findViewById<View>(R.id.rvFeatures).visibility = View.VISIBLE
    }

    private fun wireDrawToolbar(toolbar: View) {
        val rvColors = toolbar.findViewById<RecyclerView>(R.id.rvDrawColors)
        val slider = toolbar.findViewById<SeekBar>(R.id.sliderBrushSize)
        val btnUndo = toolbar.findViewById<View>(R.id.btnUndoDraw)
        val btnDone = toolbar.findViewById<View>(R.id.btnDoneDraw)
        val btnPen = toolbar.findViewById<TextView>(R.id.btnBrushPen)
        val btnMarker = toolbar.findViewById<TextView>(R.id.btnBrushMarker)
        val btnHighlighter = toolbar.findViewById<TextView>(R.id.btnBrushHighlighter)
        val btnEraser = toolbar.findViewById<TextView>(R.id.btnEraser)

        rvColors.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvColors.adapter = ColorSwatchAdapter(
            onColorPicked = { color ->
                drawCanvasView.currentColor = color
                drawCanvasView.isEraserMode = false
            }
        )

        slider.max = 100
        slider.progress = 20
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 0.002 (chhota) se 0.06 (bada) tak map kiya hai -- canvas ke min-dimension ka fraction
                drawCanvasView.currentWidthFraction = 0.002f + (progress / 100f) * 0.058f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnUndo.setOnClickListener { drawCanvasView.undoLastStroke() }
        btnDone.setOnClickListener { exitDrawMode() }

        fun highlight(selected: TextView) {
            listOf(btnPen, btnMarker, btnHighlighter, btnEraser).forEach {
                it.setBackgroundColor(if (it == selected) 0x33FFFFFF else 0x00000000)
            }
        }
        fun selectBrush(brush: BrushType, btn: TextView) {
            drawCanvasView.currentBrush = brush
            drawCanvasView.isEraserMode = false
            highlight(btn)
        }
        btnPen.setOnClickListener { selectBrush(BrushType.PEN, btnPen) }
        btnMarker.setOnClickListener { selectBrush(BrushType.MARKER, btnMarker) }
        btnHighlighter.setOnClickListener { selectBrush(BrushType.HIGHLIGHTER, btnHighlighter) }
        btnEraser.setOnClickListener {
            drawCanvasView.isEraserMode = true
            highlight(btnEraser)
        }

        highlight(btnPen)
    }

    @SuppressLint("StaticFieldLeak")
    private fun exportVideo() {
        val uri = videoUri ?: return
        if (isExporting) return
        isExporting = true
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()

        val dims = queryVideoDimensions(uri)
        VideoExporter(this).export(
            uri, dims.first, dims.second, overlayManager.getCurrentState().items,
            selectedMusicUri, isOriginalAudioMuted
        ) { success, outputUri, message ->
            runOnUiThread {
                isExporting = false
                Toast.makeText(this, if (success) "Saved to gallery ✓" else (message ?: "Export failed"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryVideoDimensions(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            w to h
        } catch (e: Exception) { 1080 to 1920 }
        finally { try { retriever.release() } catch (_: Exception) {} }
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
        musicPlayer?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
        musicPlayer?.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        musicPlayer?.release()
        musicPlayer = null
    }
}