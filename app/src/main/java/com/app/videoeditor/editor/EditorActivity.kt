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
            FeatureItem("Filter", R.drawable.ic_filter),
            FeatureItem("Draw", R.drawable.ic_draw),
            FeatureItem("Download", R.drawable.ic_download)
        )
        val rv = findViewById<RecyclerView>(R.id.rvFeatures)
        rv.setHasFixedSize(true)
        rv.adapter = FeatureAdapter(features) { item ->
            when (item.title) {
                "Text" -> showAddTextDialog()
                "Sticker" -> overlayManager.addSticker("😀")
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

    private fun setupOverlayManager() {
        overlayManager = OverlayManager(this, stickerOverlay) {
            historyManager.push(overlayManager.getCurrentState())
            updateUndoRedoButtons()
        }
        historyManager.push(overlayManager.getCurrentState()) // Initial state
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

    @SuppressLint("StaticFieldLeak")
    private fun exportVideo() {
        val uri = videoUri ?: return
        if (isExporting) return
        isExporting = true
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()

        val dims = queryVideoDimensions(uri)
        VideoExporter(this).export(uri, dims.first, dims.second, overlayManager.getCurrentState().items) { success, outputUri, message ->
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}