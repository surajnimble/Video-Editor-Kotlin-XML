package com.app.videoeditor.core

sealed class OverlayItem {
    abstract val centerX: Float
    abstract val centerY: Float
    abstract val size: Float
    abstract val rotation: Float
}

data class EditorSticker(
    val emoji: String,
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
    val backgroundColor: Int = 0xCC000000.toInt()
) : OverlayItem()

data class EditorState(
    val items: List<OverlayItem> = emptyList()
)