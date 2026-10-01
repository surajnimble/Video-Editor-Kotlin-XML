package com.app.videoeditor.editor

import android.content.Context
import android.net.Uri

/**
 * Sticker picker grid ki data source. Emoji built-in hain aur kaam karenge.
 *
 * Bundled stickers:
 * - GIF  -> res/raw/  (android.resource://<pkg>/raw/<name>)
 * - PNG  -> res/drawable/ (android.resource://<pkg>/drawable/<name>)
 *
 * Naya sticker add karna ho to file us folder me daalo aur neeche
 * rawGifs / stickerPngs list me naam add kar do -- bas.
 */
object StickerCatalog {

    sealed class Item {
        data class Emoji(val emoji: String) : Item()
        data class Drawable(val uri: Uri) : Item()
        object GalleryPicker : Item()
    }

    private fun raw(context: Context, name: String): Uri =
        Uri.parse("android.resource://${context.packageName}/raw/$name")

    private fun drawable(context: Context, name: String): Uri =
        Uri.parse("android.resource://${context.packageName}/drawable/$name")

    private val rawGifs = listOf(
        "happy_dance_sticker",
        "celebrate_happy_birthday_sticker",
        "happy_birthday_success",
        "happy_dance_sticker",
        "cute_sushi_sticker",
        "sushi_sticker_by_sukrin"
    )

    private val stickerPngs = listOf(
        "adopt",
        "dog_lover",
        "fish",
        "have_a_nice_day",
        "pet_food"
    )

    private val emojis = listOf(
        "\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDD25",
        "\u2764\uFE0F", "\uD83D\uDC4D", "\uD83C\uDF89", "\uD83D\uDE0E",
        "\uD83D\uDE22", "\uD83D\uDE21", "\uD83E\uDD73", "\uD83D\uDCAF"
    )

    fun all(context: Context): List<Item> = buildList {
        add(Item.GalleryPicker)
        rawGifs.forEach { add(Item.Drawable(raw(context, it))) }
        stickerPngs.forEach { add(Item.Drawable(drawable(context, it))) }
        emojis.forEach { add(Item.Emoji(it)) }
    }
}