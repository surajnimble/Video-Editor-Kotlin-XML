package com.app.videoeditor.editor

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.google.android.material.bottomsheet.BottomSheetDialog

class MusicBottomSheet(
    private val context: Context,
    private val songs: List<MusicCatalog.Song>,
    private val onPicked: (MusicCatalog.Song?) -> Unit,
    private val onCancelled: () -> Unit = {} // FIX: naya
) {
    fun show() {
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(R.layout.bottom_sheet_music)

        val rv = dialog.findViewById<RecyclerView>(R.id.rvSongs) ?: return
        rv.layoutManager = LinearLayoutManager(context)

        var picked = false // FIX: taaki pick hone par dismiss listener cancel na maane
        rv.adapter = MusicAdapter(songs) { song ->
            picked = true
            onPicked(song)
            dialog.dismiss()
        }

        // FIX: sheet swipe-down/tap-outside/back se bhi band ho sakti hai --
        // us case me bhi caller ko pata chalna chahiye.
        dialog.setOnDismissListener {
            if (!picked) onCancelled()
        }
        dialog.show()
    }
}