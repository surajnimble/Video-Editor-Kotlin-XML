package com.app.videoeditor.core

class HistoryManager<T>(private val maxSize: Int = 20) {
    private val undoStack = mutableListOf<T>()
    private val redoStack = mutableListOf<T>()

    fun push(state: T) {
        undoStack.add(state)
        redoStack.clear() // Clear redo history on new action
        if (undoStack.size > maxSize) undoStack.removeAt(0)
    }

    fun undo(): T? = if (undoStack.size > 1) {
        redoStack.add(undoStack.removeAt(undoStack.size - 1))
        undoStack.last()
    } else null

    fun redo(): T? = if (redoStack.isNotEmpty()) {
        val state = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(state)
        state
    } else null

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}