package com.app.videoeditor.lut

import java.io.InputStream

/**
 * Parses the .3dl (Lustre) LUT format. Unlike .cube, .3dl has no single official spec -
 * this handles the common real-world shape:
 *
 *   [optional mesh header line: N input code values, e.g. "0 32 64 ... 1023"]
 *   size^3 lines of "r g b" integers (commonly 10-bit: 0-1023, sometimes 12-bit: 0-4095,
 *   occasionally 16-bit: 0-65535)
 *
 * Bit depth is auto-detected from the largest value actually seen in the file rather than
 * assumed, since different tools export different depths.
 */
object Lut3dlParser {

    fun parse(input: InputStream): LutData.Lut3D {
        val lines = input.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (lines.isEmpty()) throw LutParseException(".3dl file is empty")

        val triplets = ArrayList<Triple<Int, Int, Int>>()
        var maxValueSeen = 0

        for (line in lines) {
            val tokens = line.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            when {
                tokens.size == 3 -> {
                    triplets.add(Triple(tokens[0], tokens[1], tokens[2]))
                    maxValueSeen = maxOf(maxValueSeen, tokens[0], tokens[1], tokens[2])
                }
                tokens.size > 3 -> {
                    // Mesh header line describing input code values - not part of the LUT data itself.
                }
                else -> throw LutParseException("Unexpected line in .3dl file: \"$line\"")
            }
        }

        val size = Math.round(Math.cbrt(triplets.size.toDouble())).toInt()
        if (size * size * size != triplets.size) {
            throw LutParseException(
                "${triplets.size} RGB entries isn't a perfect cube - can't determine LUT size for this .3dl file"
            )
        }

        val maxRange = when {
            maxValueSeen <= 255 -> 255f
            maxValueSeen <= 1023 -> 1023f
            maxValueSeen <= 4095 -> 4095f
            else -> 65535f
        }

        val data = FloatArray(size * size * size * 3)
        for (i in triplets.indices) {
            val (r, g, b) = triplets[i]
            data[i * 3] = r / maxRange
            data[i * 3 + 1] = g / maxRange
            data[i * 3 + 2] = b / maxRange
        }

        return LutData.Lut3D(size, data)
    }
}
