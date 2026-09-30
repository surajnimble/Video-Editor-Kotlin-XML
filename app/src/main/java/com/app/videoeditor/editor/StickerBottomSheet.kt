package com.app.videoeditor.editor

import android.content.Context
import android.net.Uri
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.google.android.material.bottomsheet.BottomSheetDialog

class StickerBottomSheet(
    private val context: Context,
    private val items: List<StickerCatalog.Item>,
    private val onEmojiPicked: (String) -> Unit,
    private val onImagePicked: (Uri) -> Unit,
    private val onPickFromGallery: () -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(R.layout.bottom_sheet_stickers)

        val rv = dialog.findViewById<RecyclerView>(R.id.rvStickers) ?: return
        rv.layoutManager = GridLayoutManager(context, 4)
        rv.adapter = StickerAdapter(items) { item ->
            when (item) {
                is StickerCatalog.Item.Emoji -> onEmojiPicked(item.emoji)
                is StickerCatalog.Item.Drawable -> onImagePicked(item.uri)
                StickerCatalog.Item.GalleryPicker -> onPickFromGallery()
            }
            dialog.dismiss()
        }
        dialog.show()
    }
}