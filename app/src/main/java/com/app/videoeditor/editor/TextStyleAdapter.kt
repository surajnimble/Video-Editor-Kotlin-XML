package com.app.videoeditor.editor

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.app.videoeditor.widget.TextStyles

class TextStyleAdapter(
    private val onSelected: (TextStyles.Style) -> Unit
) : RecyclerView.Adapter<TextStyleAdapter.VH>() {

    private var selectedId = TextStyles.ALL.first().id

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvFontStyle: TextView = view.findViewById(R.id.tvFontStyle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_option_styles, parent, false)
        return VH(view)
    }

    override fun getItemCount() = TextStyles.ALL.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val style = TextStyles.ALL[position]
        val isSelected = style.id == selectedId

        holder.tvFontStyle.text = style.label
        holder.tvFontStyle.typeface = style.typeface
        // Selected pill = white rounded background + black text (Image 2 me
        // "Signature" jaise highlight hai), baaki transparent + white text.
        holder.tvFontStyle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(if (isSelected) Color.WHITE else Color.TRANSPARENT)
        }
        holder.tvFontStyle.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
        holder.tvFontStyle.setPadding(28, 14, 28, 14)

        holder.itemView.setOnClickListener {
            val prevPosition = TextStyles.ALL.indexOfFirst { it.id == selectedId }
            selectedId = style.id
            notifyItemChanged(prevPosition)
            notifyItemChanged(position)
            onSelected(style)
        }
    }
}