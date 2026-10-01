package com.app.videoeditor.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R

class MusicAdapter(
    private val songs: List<MusicCatalog.Song>,
    private val onPicked: (MusicCatalog.Song?) -> Unit // null = "No Music" (hatao)
) : RecyclerView.Adapter<MusicAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSongTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(view)
    }

    // +1 -- sabse upar "No Music" option (currently selected music hatane ke liye)
    override fun getItemCount() = songs.size + 1

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == 0) {
            holder.title.text = "No Music"
            holder.itemView.setOnClickListener { onPicked(null) }
            return
        }
        val song = songs[position - 1]
        holder.title.text = song.title
        holder.itemView.setOnClickListener { onPicked(song) }
    }
}