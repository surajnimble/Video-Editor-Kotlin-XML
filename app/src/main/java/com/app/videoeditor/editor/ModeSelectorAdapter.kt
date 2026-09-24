package com.app.videoeditor.editor

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.app.videoeditor.R

enum class TextToolMode { FONT, TEXT_COLOR, EFFECTS, ALIGN, BACKGROUND }

/**
 * Bottom toolbar (rvBottomOptions) -- 5 fixed mode buttons. FONT/TEXT_COLOR/
 * EFFECTS/BACKGROUND tap karne par sirf "active mode" badalta hai (upar wala
 * rvOptionStyles panel AddTextDialog me uske hisaab se refresh hota hai).
 * ALIGN khud hi apni state cycle karta hai (L -> C -> R -> L...), koi
 * sub-panel nahi khulta uske liye.
 *
 * Icons drawable resources se aate hain -- jaise hi apne `ic_*` drawables
 * add kar do, bas ICON_RES map me unhe point kar dena.
 */
class ModeSelectorAdapter(
    private val onModeTapped: (TextToolMode) -> Unit
) : RecyclerView.Adapter<ModeSelectorAdapter.VH>() {

    private var activeMode = TextToolMode.FONT
    var currentAlignment = 1 // 0 = left, 1 = center, 2 = right (default center)

    companion object {
        private val ICON_RES = mapOf(
            TextToolMode.FONT to R.drawable.ic_text,
            TextToolMode.TEXT_COLOR to R.drawable.ic_circular_color_wheel,
            TextToolMode.EFFECTS to R.drawable.ic_effects,
            TextToolMode.BACKGROUND to R.drawable.ic_text_background
        )
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivOptionIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bottom_option, parent, false)
        return VH(view)
    }

    override fun getItemCount() = TextToolMode.values().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val mode = TextToolMode.values()[position]
        val isActive = mode == activeMode

        // ALIGN icon selected alignment ke hisaab se badalta hai (L/C/R),
        // baaki modes static ic_* drawables use karte hain.
        val iconRes = if (mode == TextToolMode.ALIGN) {
            when (currentAlignment) {
                0 -> R.drawable.ic_align_left
                2 -> R.drawable.ic_align_right
                else -> R.drawable.ic_align_center
            }
        } else {
            ICON_RES.getValue(mode)
        }
        holder.icon.setImageResource(iconRes)
        holder.itemView.background = ring(isActive)

        holder.itemView.setOnClickListener {
            val prev = TextToolMode.values().indexOf(activeMode)
            activeMode = mode
            onModeTapped(mode)
            notifyItemChanged(prev)
            notifyItemChanged(position)
        }
    }

    fun setAlignment(alignIndex: Int) {
        if (currentAlignment != alignIndex) {
            currentAlignment = alignIndex
            notifyItemChanged(TextToolMode.values().indexOf(TextToolMode.ALIGN))
        }
    }

    private fun ring(isActive: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 8f
        setColor(if (isActive) 0x33FFFFFF else Color.TRANSPARENT)
    }
}