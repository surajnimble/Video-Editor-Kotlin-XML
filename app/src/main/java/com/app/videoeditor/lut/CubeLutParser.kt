package com.app.videoeditor.lut

import java.io.InputStream

/**
 * Parses the .cube LUT format (the de-facto industry standard - DaVinci Resolve, Adobe,
 * Final Cut all export this). Spec: https://wwwimages2.adobe.com/content/dam/acom/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf
 *
 * Supports both LUT_1D_SIZE and LUT_3D_SIZE header variants in the same file format.
 * Ignores DOMAIN_MIN / DOMAIN_MAX (assumes the standard 0..1 domain, true for the vast
 * majority of real-world .cube files) and TITLE / comment lines.
 */
object CubeLutParser {

    fun parse(input: InputStream): LutData {
        val lines = input.bufferedReader().readLines()

        var size1D: Int? = null
        var size3D: Int? = null
        val values = ArrayList<FloatArray>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            when {
                line.startsWith("TITLE") -> continue
                line.startsWith("DOMAIN_MIN") -> continue
                line.startsWith("DOMAIN_MAX") -> continue
                line.startsWith("LUT_1D_SIZE") -> {
                    size1D = line.substringAfter("LUT_1D_SIZE").trim().toIntOrNull()
                        ?: throw LutParseException("Malformed LUT_1D_SIZE line: $line")
                }
                line.startsWith("LUT_3D_SIZE") -> {
                    size3D = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull()
                        ?: throw LutParseException("Malformed LUT_3D_SIZE line: $line")
                }
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            values.add(floatArrayOf(r, g, b))
                        }
                    }
                }
            }
        }

        if (size3D != null) {
            val expected = size3D * size3D * size3D
            if (values.size != expected) {
                throw LutParseException(
                    "Expected $expected RGB triplets for LUT_3D_SIZE=$size3D, found ${values.size}"
                )
            }
            val flat = FloatArray(expected * 3)
            for (i in 0 until expected) {
                flat[i * 3] = values[i][0]
                flat[i * 3 + 1] = values[i][1]
                flat[i * 3 + 2] = values[i][2]
            }
            return LutData.Lut3D(size3D, flat)
        }

        if (size1D != null) {
            if (values.size != size1D) {
                throw LutParseException("Expected $size1D entries for LUT_1D_SIZE=$size1D, found ${values.size}")
            }
            val r = FloatArray(size1D)
            val g = FloatArray(size1D)
            val b = FloatArray(size1D)
            for (i in 0 until size1D) {
                r[i] = values[i][0]
                g[i] = values[i][1]
                b[i] = values[i][2]
            }
            return LutData.Lut1D(size1D, r, g, b)
        }

        throw LutParseException("File has neither LUT_1D_SIZE nor LUT_3D_SIZE - is this really a .cube file?")
    }
}
