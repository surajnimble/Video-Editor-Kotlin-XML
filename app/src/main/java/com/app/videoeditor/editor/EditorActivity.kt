package com.app.videoeditor.editor

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import com.app.videoeditor.R
import com.app.videoeditor.core.EditorState
import com.app.videoeditor.core.HistoryManager
import com.app.videoeditor.core.OverlayManager

class EditorActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var stickerOverlay: android.widget.FrameLayout
    private lateinit var exportButton: TextView
    private lateinit var btnUndo: TextView
    private lateinit var btnRedo: TextView

    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null

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
        exportButton = findViewById(R.id.exportButton)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddSticker).setOnClickListener { overlayManager.addSticker("😀") }
        findViewById<View>(R.id.btnAddText).setOnClickListener { showAddTextDialog() }
        exportButton.setOnClickListener { exportVideo() }

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

    private fun showAddTextDialog() {
        val input = EditText(this).apply { hint = "Enter text"; setText("Hello") }
        AlertDialog.Builder(this)
            .setTitle("Add Text")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().ifBlank { "Text" }
                overlayManager.addText(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        exportButton.isEnabled = false
        exportButton.text = "Exporting…"

        val dims = queryVideoDimensions(uri)
        VideoExporter(this).export(uri, dims.first, dims.second, overlayManager.getCurrentState().items) { success, outputUri, message ->
            runOnUiThread {
                exportButton.isEnabled = true
                exportButton.text = "Export"
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