package com.app.videoeditor.editor

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.app.videoeditor.widget.TextEffects

class TextEffectAdapter(
    private val onSelected: (TextEffects.Effect) -> Unit
) : RecyclerView.Adapter<TextEffectAdapter.VH>() {

    private var selectedId = TextEffects.ALL.first().id

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvFontStyle: TextView = view.findViewById(R.id.tvFontStyle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_option_styles, parent, false)
        return VH(view)
    }

    override fun getItemCount() = TextEffects.ALL.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val effect = TextEffects.ALL[position]
        val isSelected = effect.id == selectedId

        holder.tvFontStyle.text = effect.label
        holder.tvFontStyle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(if (isSelected) Color.WHITE else Color.TRANSPARENT)
        }
        holder.tvFontStyle.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
        holder.tvFontStyle.setPadding(28, 14, 28, 14)

        holder.itemView.setOnClickListener {
            val prev = TextEffects.ALL.indexOfFirst { it.id == selectedId }
            selectedId = effect.id
            notifyItemChanged(prev)
            notifyItemChanged(position)
            onSelected(effect)
        }
    }
}