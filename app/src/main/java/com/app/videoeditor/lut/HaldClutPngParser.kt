package com.app.videoeditor.lut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import kotlin.math.round

/**
 * Parses a Hald CLUT image (used by GIMP, ImageMagick, RawTherapee, many mobile filter
 * packs). A Hald CLUT is a square PNG that IS the LUT: it's an identity color cube
 * flattened into a grid image. Applying a color transform to that image and re-exporting
 * it as a PNG is how these LUTs are authored/distributed in the first place.
 *
 * Math: for a LUT of `size` colors per channel, the image holds size^3 pixels laid out
 * row-major, matching the same R-fastest/G/B ordering .cube files use. So:
 *   size = round( (width * height) ^ (1/3) )
 * and pixel index i (row-major) maps directly to LUT index i.
 */
object HaldClutPngParser {

    fun parse(input: InputStream): LutData.Lut3D {
        val bitmap = BitmapFactory.decodeStream(input)
            ?: throw LutParseException("Could not decode PNG - file may be corrupt or not an image")

        val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
        val size = round(Math.cbrt(totalPixels.toDouble())).toInt()

        if (size.toLong() * size.toLong() * size.toLong() != totalPixels) {
            throw LutParseException(
                "Image is ${bitmap.width}x${bitmap.height} (${totalPixels} px), which isn't a " +
                    "perfect cube of pixels - this doesn't look like a valid Hald CLUT identity image"
            )
        }

        val data = FloatArray(size * size * size * 3)
        val pixels = IntArray(totalPixels.toInt())
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()

        for (i in pixels.indices) {
            val p = pixels[i]
            data[i * 3] = ((p shr 16) and 0xFF) / 255f
            data[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            data[i * 3 + 2] = (p and 0xFF) / 255f
        }

        return LutData.Lut3D(size, data)
    }
}
