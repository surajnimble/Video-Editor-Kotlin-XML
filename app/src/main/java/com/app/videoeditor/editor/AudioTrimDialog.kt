package com.app.videoeditor.editor

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.app.videoeditor.R
import com.app.videoeditor.trimmer.AudioTrimmerController
import com.app.videoeditor.trimmer.TrimTimelineView
import java.io.File

class AudioTrimDialog(
    private val activity: Activity,
    private val audioUri: Uri,
    private val onTrimmed: (File) -> Unit,
    private val onCancelled: () -> Unit = {} // FIX: naya -- cancel/dismiss hone par caller ko pata chale
) {
    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_audio_trim)
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)

        val timeline = dialog.findViewById<TrimTimelineView>(R.id.timeline)
        val tapToPlayArea = dialog.findViewById<FrameLayout>(R.id.tapToPlayArea)
        val playIcon = dialog.findViewById<ImageView>(R.id.ivPlay)
        val timeLabel = dialog.findViewById<TextView>(R.id.timeLabel)
        val slider = dialog.findViewById<SeekBar>(R.id.slider)
        val btnDone = dialog.findViewById<View>(R.id.btnDone)
        val btnCancel = dialog.findViewById<View>(R.id.btnCancel)

        val controller = AudioTrimmerController(
            context = activity,
            tapToPlayArea = tapToPlayArea,
            playIcon = playIcon,
            playIconRes = android.R.drawable.ic_media_play,
            pauseIconRes = android.R.drawable.ic_media_pause,
            timeLabel = timeLabel,
            timeline = timeline,
            doneButton = btnDone,
            slider = slider
        )
        controller.setMinTrimSeconds(1f)

        var trimmedSuccessfully = false // FIX: taaki dismiss par onCancelled double-fire na ho
        controller.trimListener = object : AudioTrimmerController.TrimListener {
            override fun onTrimStart() {
                btnDone.isEnabled = false
            }
            override fun onTrimSuccess(file: File) {
                trimmedSuccessfully = true
                controller.release()
                dialog.dismiss()
                onTrimmed(file)
            }
            override fun onTrimError(msg: String) {
                btnDone.isEnabled = true
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
        controller.setAudio(audioUri)

        btnCancel.setOnClickListener {
            controller.release()
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            controller.release()
            if (!trimmedSuccessfully) onCancelled() // FIX
        }
        dialog.show()
    }
}