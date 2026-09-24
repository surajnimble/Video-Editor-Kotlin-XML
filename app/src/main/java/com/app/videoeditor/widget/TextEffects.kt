package com.app.videoeditor.widget

/**
 * Text "effects" -- abhi sirf STATIC visual variation hain (letter-spacing,
 * scale, ya zigzag offset). Real time-synced reveal animation (Instagram
 * jaisa frame-by-frame typewriter/pop/jump) is scope me nahi hai -- uske
 * liye export pipeline ko per-frame timing chahiye hoga, wo alag kaam hai.
 */
object TextEffects {
    data class Effect(val id: String, val label: String)

    val ALL = listOf(
        Effect("none", "None"),
        Effect("typewriter", "Typewriter"),
        Effect("pop", "Pop"),
        Effect("jump", "Jump")
    )

    fun byId(id: String): Effect = ALL.firstOrNull { it.id == id } ?: ALL[0]
}