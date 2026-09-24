package com.app.videoeditor.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R

data class FeatureItem(
    val title: String,
    val iconRes: Int
)

class FeatureAdapter(
    private val items: List<FeatureItem>,
    private val onClick: (FeatureItem) -> Unit
) : RecyclerView.Adapter<FeatureAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivFeatures)
        val title: TextView = view.findViewById(R.id.tvFeatures)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.items_features, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.icon.setImageResource(item.iconRes)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
