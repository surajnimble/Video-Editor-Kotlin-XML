package com.app.videoeditor.core

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.app.videoeditor.widget.DraggableTextView
import com.app.videoeditor.widget.StickerView

class OverlayManager(
    private val context: Context,
    private val overlayContainer: FrameLayout,
    private val onStateChanged: () -> Unit
) {
    private val views = mutableMapOf<View, OverlayItem>()

    fun addSticker(emoji: String) {
        val view = StickerView(context).apply {
            this.emoji = emoji; fractionCenterX = 0.5f; fractionCenterY = 0.5f; fractionSize = 0.18f
            onActionEnded = { saveState() }
            onTapped = { selectView(it) }
        }
        addItem(view, EditorSticker(emoji, 0.5f, 0.5f, 0.18f, 0f))
    }

    fun addText(
        text: String,
        textColor: Int = 0xFFFFFFFF.toInt(),
        backgroundColor: Int = 0xCC000000.toInt(),
        fontStyleId: String = "classic",
        textEffectId: String = "none",
        contentAlignment: Int = DraggableTextView.ALIGN_CENTER
    ) {
        val view = DraggableTextView(context).apply {
            this.displayText = text; fractionCenterX = 0.5f; fractionCenterY = 0.5f; fractionSize = 0.15f
            this.textColor = textColor
            this.textBackgroundColor = backgroundColor
            this.fontStyleId = fontStyleId
            this.textEffectId = textEffectId
            this.contentAlignment = contentAlignment
            onActionEnded = { saveState() }
            onTapped = { selectView(it) }
        }
        addItem(view, EditorText(text, 0.5f, 0.5f, 0.15f, 0f, textColor, backgroundColor, fontStyleId, textEffectId, contentAlignment))
    }

    private fun addItem(view: View, item: OverlayItem) {
        deselectAll()
        views[view] = item
        overlayContainer.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        saveState()
    }

    fun getCurrentState(): EditorState {
        val items = views.mapNotNull { (view, item) ->
            when (view) {
                is StickerView -> EditorSticker(view.emoji, view.fractionCenterX, view.fractionCenterY, view.fractionSize, view.rotationDegrees)
                is DraggableTextView -> EditorText(
                    view.displayText, view.fractionCenterX, view.fractionCenterY, view.fractionSize, view.rotationDegrees,
                    view.textColor, view.textBackgroundColor, view.fontStyleId, view.textEffectId, view.textAlignment
                )
                else -> null
            }
        }
        return EditorState(items)
    }

    fun restoreState(state: EditorState) {
        overlayContainer.removeAllViews()
        views.clear()
        state.items.forEach { item ->
            when (item) {
                is EditorSticker -> {
                    val view = StickerView(context).apply {
                        this.emoji = item.emoji; fractionCenterX = item.centerX; fractionCenterY = item.centerY
                        fractionSize = item.size; rotationDegrees = item.rotation
                        onActionEnded = { saveState() }
                        onTapped = { selectView(it) }
                    }
                    views[view] = item; overlayContainer.addView(view)
                }
                is EditorText -> {
                    val view = DraggableTextView(context).apply {
                        this.displayText = item.text; fractionCenterX = item.centerX; fractionCenterY = item.centerY
                        fractionSize = item.size; rotationDegrees = item.rotation; textColor = item.color
                        textBackgroundColor = item.backgroundColor
                        fontStyleId = item.fontStyleId
                        textEffectId = item.textEffectId
                        textAlignment = item.contentAlignment
                        onActionEnded = { saveState() }
                        onTapped = { selectView(it) }
                    }
                    views[view] = item; overlayContainer.addView(view)
                }
            }
        }
        deselectAll()
    }

    fun selectView(view: View) {
        deselectAll()
        (view as? StickerView)?.isStickerSelected = true
        (view as? DraggableTextView)?.isItemActive = true
    }

    fun deselectAll() {
        views.keys.forEach {
            (it as? StickerView)?.isStickerSelected = false
            (it as? DraggableTextView)?.isItemActive = false
        }
    }

    private fun saveState() {
        deselectAll()
        onStateChanged()
    }

    fun clear() {
        overlayContainer.removeAllViews()
        views.clear()
        saveState()
    }
}