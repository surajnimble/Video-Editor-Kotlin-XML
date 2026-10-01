package com.app.videoeditor.editor

import android.content.Context
import android.net.Uri

/**
 * Music picker ki data source.
 *
 * Apna gaana add karne ke liye:
 * 1. File ka naam lowercase + underscore me rakho (jaise "chill_beat.mp3")
 * 2. Usse res/raw/ folder me daalo
 * 3. Neeche songFileNames list me "file_name" to "Display Name" add kar do -- bas.
 */
object MusicCatalog {

    data class Song(val title: String, val uri: Uri)

    private val songFileNames = listOf(
        "alex_morgan_pop" to "Alex Morgan - Pop",
        "easy_eva_happiness_let_it_go" to "Easy Eva - Happiness",
        "folk_tales_calm_carefree" to "Folk Tales - Calm Carefree",
        "loksii_vlogging_beat" to "Loksii - Vlogging Beat",
        "prettyjohn1_beat" to "Prettyjohn1 - Beat",
        "vibemode_background" to "Vibemode - Background"
        // yahan apne songs add karo
    )

    fun all(context: Context): List<Song> = songFileNames.map { (fileName, title) ->
        Song(title, Uri.parse("android.resource://${context.packageName}/raw/$fileName"))
    }
}