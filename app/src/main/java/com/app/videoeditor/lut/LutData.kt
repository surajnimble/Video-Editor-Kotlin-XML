package com.app.videoeditor.lut

/**
 * Every supported LUT format gets parsed down into one of these two shapes, so the
 * renderer only needs two shader paths total, no matter how many file formats exist.
 * All values are normalized to 0f..1f.
 */
sealed class LutData {

    /** Independent per-channel curve - e.g. a simple 1D LUT / tone curve. size entries per channel. */
    data class Lut1D(
        val size: Int,
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray
    ) : LutData()

    /**
     * Full 3D color cube, size x size x size entries, RGB triplets, stored in .cube's
     * standard ordering: red fastest-changing, then green, then blue.
     * data.size must equal size*size*size*3.
     */
    data class Lut3D(
        val size: Int,
        val data: FloatArray
    ) : LutData()
}

class LutParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedLutFormatException(format: String) : Exception(
    "$format is a proprietary format with no public spec, so it can't be parsed reliably. " +
        "Convert it to .cube first (several free LUT converters do this), then load the .cube file."
)
