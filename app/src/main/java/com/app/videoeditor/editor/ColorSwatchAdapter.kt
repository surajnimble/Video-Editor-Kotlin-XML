package com.app.videoeditor.editor

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R

/**
 * Text-color aur background-color dono mode isi ek adapter ko reuse karte
 * hain. includeNoneOption=true karne par pehla item "No background"
 * (transparent) hota hai -- sirf background mode ke liye pass karo.
 *
 * Har item ek color chip hai (item_color_swatch -- white frame + CardView).
 * Selected chip par white 4dp stroke (bg_white_corner_4) foreground ke roop
 * me lagta hai. Eyedropper / custom-color button ab adapter me nahi --
 * dialog ke `ivColorPicker` me chala gaya hai.
 *
 * SWATCHES list me apne custom colors yahan daalo (inbuilt list ko replace
 * kar dena).
 */
class ColorSwatchAdapter(
    private val onColorPicked: (Int) -> Unit,
    private val includeNoneOption: Boolean = false
) : RecyclerView.Adapter<ColorSwatchAdapter.VH>() {

    companion object {
        // Apne colors yahan daalo -- inbuilt list replace kar di
        val SWATCHES = listOf(
            Color.WHITE, Color.BLACK, 0xFF4A90E2.toInt(), 0xFF4CAF50.toInt(),
            0xFFFFD60A.toInt(), 0xFFFF9500.toInt(), 0xFFFF3B30.toInt(),
            0xFFFF2D78.toInt(), 0xFFAF52DE.toInt()
        )
    }

    private val noneOffset = if (includeNoneOption) 1 else 0
    private var selectedIndex = if (includeNoneOption) 0 else 0

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.ivColorS)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_color_swatch, parent, false)
        return VH(view)
    }

    override fun getItemCount() = SWATCHES.size + noneOffset

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.itemView.foreground = null

        if (includeNoneOption && position == 0) {
            holder.card.setCardBackgroundColor(Color.DKGRAY)
            holder.itemView.foreground = if (position == selectedIndex) ring(holder.itemView.context) else null
            holder.itemView.setOnClickListener {
                select(position)
                onColorPicked(Color.TRANSPARENT)
            }
            return
        }

        val color = SWATCHES[position - noneOffset]
        holder.card.setCardBackgroundColor(color)
        holder.itemView.foreground = if (position == selectedIndex) ring(holder.itemView.context) else null
        holder.itemView.setOnClickListener {
            select(position)
            onColorPicked(color)
        }
    }

    private fun select(position: Int) {
        val prev = selectedIndex
        selectedIndex = position
        notifyItemChanged(prev)
        notifyItemChanged(position)
    }

    private fun ring(context: Context) = ContextCompat.getDrawable(context, R.drawable.bg_transparent_corner_3)
}