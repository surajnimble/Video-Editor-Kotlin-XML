package com.app.videoeditor.core

import com.app.videoeditor.widget.DrawStroke

sealed class OverlayItem {
    abstract val centerX: Float
    abstract val centerY: Float
    abstract val size: Float
    abstract val rotation: Float
}

data class EditorSticker(
    val emoji: String = "",
    val imageUri: String? = null,
    override val centerX: Float,
    override val centerY: Float,
    override val size: Float,
    override val rotation: Float
) : OverlayItem()

data class EditorText(
    val text: String,
    override val centerX: Float,
    override val centerY: Float,
    override val size: Float,
    override val rotation: Float,
    val color: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xCC000000.toInt(),
    val fontStyleId: String = "classic",
    val textEffectId: String = "none",
    val contentAlignment: Int = 1
) : OverlayItem()

data class EditorDrawing(
    val strokes: List<DrawStroke>
) : OverlayItem() {
    override val centerX = 0.5f
    override val centerY = 0.5f
    override val size = 1f
    override val rotation = 0f
}

data class EditorState(
    val items: List<OverlayItem> = emptyList()
)