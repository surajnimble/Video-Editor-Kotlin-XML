package com.app.videoeditor.editor

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R

class StickerAdapter(
    private val items: List<StickerCatalog.Item>,
    private val onPicked: (StickerCatalog.Item) -> Unit
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.ivStickerPreview)
        val emojiText: TextView = view.findViewById(R.id.tvStickerEmoji)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        when (item) {
            is StickerCatalog.Item.Emoji -> {
                holder.emojiText.visibility = View.VISIBLE
                holder.image.visibility = View.GONE
                holder.emojiText.text = item.emoji
            }
            is StickerCatalog.Item.Drawable -> {
                holder.emojiText.visibility = View.GONE
                holder.image.visibility = View.VISIBLE
                bindThumbnail(holder.image, item.uri)
            }
            StickerCatalog.Item.GalleryPicker -> {
                holder.emojiText.visibility = View.GONE
                holder.image.visibility = View.VISIBLE
                holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }
        holder.itemView.setOnClickListener { onPicked(item) }
    }

    private fun bindThumbnail(imageView: ImageView, uri: Uri) {
        val requested = uri
        imageView.setImageDrawable(null)
        imageView.tag = requested
        val res = imageView.resources
        val target = (res.displayMetrics.density * 72f).toInt().coerceAtLeast(1)
        Thread {
            val drawable = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(
                        imageView.context.contentResolver,
                        requested
                    )
                    ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                        decoder.setTargetSize(target, target)
                    }
                } else {
                    imageView.context.contentResolver.openInputStream(uri)?.use { ins ->
                        BitmapDrawable(
                            res,
                            BitmapFactory.decodeStream(
                                ins,
                                null,
                                BitmapFactory.Options().apply { inSampleSize = 4 }
                            )
                        )
                    }
                }
            } catch (e: Exception) { null }
            imageView.post {
                if (imageView.tag == requested && drawable != null) {
                    if (drawable is Animatable) drawable.start()
                    imageView.setImageDrawable(drawable)
                }
            }
        }.start()
    }
}