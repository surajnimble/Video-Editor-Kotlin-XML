package com.app.videoeditor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R
import com.app.videoeditor.widget.CircleImageView

/**
 * Plain horizontal filter strip. Pair this with a LinearSnapHelper + a scroll listener in
 * the Activity/Fragment that calls [setSelectedPosition] once a new item settles in the
 * center - that's what drives the "center one is bigger with a ring" Instagram look.
 */
class FilterAdapter(
    private val items: List<FilterItem>,
    private val onFilterChosen: (FilterItem) -> Unit
) : RecyclerView.Adapter<FilterAdapter.ViewHolder>() {

    private var selectedPosition = 0
    private val selectedRingColor = 0xFFFF9500.toInt() // IG-style orange ring
    private val ringWidthPx = 4f
    private val selectedScale = 1.25f

    class ViewHolder(root: android.view.View, val thumb: CircleImageView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val root = LayoutInflater.from(parent.context).inflate(R.layout.item_filter, parent, false)
        val thumb = root.findViewById<CircleImageView>(R.id.thumb)
        return ViewHolder(root, thumb)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.thumb.setImageResource(item.thumbnailRes)

        val isSelected = position == selectedPosition
        holder.thumb.ringColor = if (isSelected) selectedRingColor else 0
        holder.thumb.ringWidthPx = if (isSelected) ringWidthPx else 0f
        val scale = if (isSelected) selectedScale else 1f
        holder.thumb.scaleX = scale
        holder.thumb.scaleY = scale

        holder.thumb.setOnClickListener {
            setSelectedPosition(position)
        }
    }

    override fun getItemCount() = items.size

    fun setSelectedPosition(position: Int) {
        if (position == selectedPosition) return
        val old = selectedPosition
        selectedPosition = position
        notifyItemChanged(old)
        notifyItemChanged(position)
        onFilterChosen(items[position])
    }

    fun getItem(position: Int) = items[position]
}
