package com.app.videoeditor.lut

import android.content.Context
import java.io.InputStream

/**
 * Single entry point: load a LUT file from assets (or any InputStream) without the rest
 * of the app needing to know which parser handles which extension.
 */
object LutLoader {

    fun loadFromAssets(context: Context, assetPath: String): LutData {
        context.assets.open(assetPath).use { stream ->
            return loadFromStream(stream, assetPath)
        }
    }

    fun loadFromStream(stream: InputStream, fileNameForFormatDetection: String): LutData {
        return when (extensionOf(fileNameForFormatDetection)) {
            "cube" -> CubeLutParser.parse(stream)
            "png" -> HaldClutPngParser.parse(stream)
            "3dl" -> Lut3dlParser.parse(stream)
            "look" -> throw UnsupportedLutFormatException(".look (Canon)")
            else -> throw LutParseException(
                "Unrecognized LUT file extension: ${fileNameForFormatDetection.substringAfterLast('.', "")}"
            )
        }
    }

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()
}
