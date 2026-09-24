package com.app.videoeditor.widget

import android.graphics.Color
import android.graphics.Typeface

/**
 * Instagram-jaise text "styles" -- har style ek typeface + default
 * text/background color combo represent karta hai. Dialog ka style-picker,
 * DraggableTextView (on-screen draw) aur VideoExporter (export render) --
 * teeno isi se typeface uthhate hain, taaki dialog preview, live overlay,
 * aur final export hamesha ek jaisa dikhein.
 */
object TextStyles {

    data class Style(
        val id: String,
        val label: String,
        val typeface: Typeface,
        val defaultTextColor: Int,
        val defaultBackgroundColor: Int
    )

    val ALL: List<Style> = listOf(
        Style("classic", "Classic", Typeface.SANS_SERIF, Color.WHITE, Color.TRANSPARENT),
        Style("signature", "Signature", Typeface.create("cursive", Typeface.ITALIC), Color.BLACK, Color.WHITE),
        Style("editor", "Editor", Typeface.MONOSPACE, Color.WHITE, Color.TRANSPARENT),
        Style("poster", "Poster", Typeface.create("sans-serif-black", Typeface.BOLD), Color.WHITE, Color.BLACK),
        Style("bold", "Bold", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), Color.WHITE, Color.TRANSPARENT)
    )

    fun byId(id: String): Style = ALL.firstOrNull { it.id == id } ?: ALL[0]
}