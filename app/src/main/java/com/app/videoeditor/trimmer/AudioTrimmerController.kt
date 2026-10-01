package com.app.videoeditor.trimmer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.Locale

/**
 * VideoTrimmerController jaisa hi, lekin AUDIO ke liye -- koi PlayerView/video
 * surface nahi chahiye, sirf playback (ExoPlayer, audio-only) + TrimTimelineView
 * (placeholder bars ke saath, real waveform nahi -- woh alag scope ka kaam hai).
 *
 * Standalone/portable: sirf Context + Uri + callbacks par depend karta hai,
 * koi Activity-specific type use nahi karta -- isliye baad me alag module me
 * nikalna ho to sirf is file + TrimTimelineView.kt + attrs file copy karne honge.
 */
class AudioTrimmerController(
    private val context: Context,
    private val tapToPlayArea: View,      // koi bhi View jisse tap karke play/pause ho
    private val playIcon: ImageView,
    private val pauseIcon: ImageView? = null,
    private val playIconRes: Int,
    private val pauseIconRes: Int,
    private val timeLabel: TextView? = null,
    private val timeline: TrimTimelineView,
    private val doneButton: View,
    private val slider: SeekBar? = null
) : TrimTimelineView.TimelineListener {

    interface TrimListener {
        fun onTrimStart()
        fun onTrimSuccess(outputFile: File)
        fun onTrimError(message: String)
    }

    var trimListener: TrimListener? = null
    var doneClickListener: View.OnClickListener? = null

    private var player: ExoPlayer? = null
    private var audioUri: Uri? = null
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var isSliderDragging = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunning = false
    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!progressRunning) return
            var pos = p.currentPosition
            if (pos >= endMs) {
                p.pause()
                p.seekTo(startMs)
                pos = startMs
                updateIcons()
            }
            timeline.updatePlayhead(pos)
            updateSlider(pos)
            updateTimeLabel(pos)
            handler.postDelayed(this, 30)
        }
    }

    init {
        tapToPlayArea.setOnClickListener { togglePlayPause() }
        doneButton.setOnClickListener { v ->
            if (doneClickListener != null) doneClickListener?.onClick(v)
            else trimAndSave()
        }
        timeline.listener = this
        updateIcons()

        slider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isSliderDragging) {
                    val totalRange = endMs - startMs
                    val seekPos = startMs + (progress * totalRange / 100)
                    player?.seekTo(seekPos)
                    timeline.updatePlayhead(seekPos)
                    updateTimeLabel(seekPos)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSliderDragging = true
                player?.pause()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSliderDragging = false
                val progress = seekBar?.progress ?: 0
                val totalRange = endMs - startMs
                val seekPos = startMs + (progress * totalRange / 100)
                player?.seekTo(seekPos)
                timeline.updatePlayhead(seekPos)
                updateTimeLabel(seekPos)
            }
        })
    }

    fun setAudio(uri: Uri) {
        audioUri = uri
        release()
        durationMs = 0L
        val p = ExoPlayer.Builder(context).build()
        player = p
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs == 0L) {
                    durationMs = p.duration
                    startMs = 0L; endMs = durationMs
                    timeline.setDuration(durationMs)
                    updateTimeLabel(0)
                    updateSlider(0)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateIcons()
                if (isPlaying) startProgress() else stopProgress()
            }
            override fun onPlayerError(error: PlaybackException) {
                trimListener?.onTrimError(error.localizedMessage ?: "Playback error")
            }
        })
        p.playWhenReady = true

        // FIX: background thread par asli waveform decode karo, ready hote hi
        // timeline khud update ho jaayega (placeholder blocks se waveform me badlega)
        Thread {
            val amplitudes = WaveformExtractor.extract(context, uri)
            handler.post { timeline.setWaveform(amplitudes) }
        }.start()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        else {
            val pos = p.currentPosition
            if (pos < startMs || pos >= endMs) p.seekTo(startMs)
            p.play()
        }
        updateIcons()
    }

    fun pauseAudio() { player?.pause() }
    fun resumeAudio() { player?.play() }

    fun getTrimStartMs() = startMs
    fun getTrimEndMs() = endMs
    fun setMinTrimSeconds(sec: Float) = timeline.setMinTrimSeconds(sec)

    fun trimAndSave() {
        val uri = audioUri ?: return
        trimListener?.onTrimStart()
        val outFile = File(context.cacheDir, "trimmed_audio_${System.currentTimeMillis()}.m4a")
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()
        val composition = Composition.Builder(
            listOf(EditedMediaItemSequence.Builder(EditedMediaItem.Builder(item).build()).build())
        ).build()
        Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    trimListener?.onTrimSuccess(outFile)
                }
                override fun onError(composition: Composition, result: ExportResult, error: ExportException) {
                    trimListener?.onTrimError(error.localizedMessage ?: "Trim failed")
                }
            })
            .build()
            .start(composition, outFile.absolutePath)
    }

    fun release() {
        stopProgress()
        player?.release()
        player = null
    }

    override fun onTrimDragStart() {
        player?.pause()
        stopProgress()
        timeline.setPlayheadVisible(false)
        updateIcons()
    }
    override fun onTrimChanged(startMs: Long, endMs: Long) {
        this.startMs = startMs; this.endMs = endMs
        updateSlider(player?.currentPosition ?: startMs)
    }
    override fun onTrimDragStop() {
        timeline.setPlayheadVisible(true)
        player?.seekTo(startMs)
        timeline.updatePlayhead(startMs)
        updateSlider(startMs)
        updateTimeLabel(startMs)
    }
    override fun onPlayheadMoved(ms: Long) {
        player?.seekTo(ms)
        updateSlider(ms)
        updateTimeLabel(ms)
    }

    private fun updateIcons() {
        val playing = player?.isPlaying == true
        if (pauseIcon == null) {
            playIcon.setImageResource(if (playing) pauseIconRes else playIconRes)
            playIcon.visibility = View.VISIBLE
        } else {
            playIcon.visibility = if (!playing) View.VISIBLE else View.GONE
            pauseIcon.visibility = if (playing) View.VISIBLE else View.GONE
        }
    }

    private fun updateTimeLabel(pos: Long) {
        timeLabel?.text = String.format(Locale.ENGLISH, "%s / %s", fmt(pos), fmt(durationMs))
    }

    private fun updateSlider(pos: Long) {
        if (slider == null || isSliderDragging) return
        val totalRange = endMs - startMs
        if (totalRange <= 0) { slider.progress = 0; return }
        val clampedPos = pos.coerceIn(startMs, endMs)
        slider.progress = ((clampedPos - startMs) * 100 / totalRange).toInt()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ENGLISH, "%d:%02d", s / 60, s % 60)
    }
    private fun startProgress() {
        if (progressRunning) return
        progressRunning = true
        handler.post(progressRunnable)
    }
    private fun stopProgress() {
        progressRunning = false
        handler.removeCallbacks(progressRunnable)
    }
}