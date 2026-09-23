package com.app.videoeditor.lut

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * CPU-side LUT application for still photos. The live preview/video path uses the GPU
 * shader in gl/LutSurfaceProcessor for performance, but a single photo is small and
 * infrequent enough that doing it on the CPU (with trilinear interpolation for smooth
 * results) is simpler than standing up a second GPU pass just for stills.
 */
object LutSoftwareApplier {

    fun apply(bitmap: Bitmap, lut: LutData): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        when (lut) {
            is LutData.Lut1D -> applyLut1D(pixels, lut)
            is LutData.Lut3D -> applyLut3D(pixels, lut)
        }

        val out = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun applyLut1D(pixels: IntArray, lut: LutData.Lut1D) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = lookup1D(lut.red, ((p shr 16) and 0xFF) / 255f)
            val g = lookup1D(lut.green, ((p shr 8) and 0xFF) / 255f)
            val b = lookup1D(lut.blue, (p and 0xFF) / 255f)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun lookup1D(curve: FloatArray, value: Float): Int {
        val pos = (value * (curve.size - 1)).coerceIn(0f, (curve.size - 1).toFloat())
        val i0 = floor(pos).toInt()
        val i1 = (i0 + 1).coerceAtMost(curve.size - 1)
        val frac = pos - i0
        val out = curve[i0] * (1 - frac) + curve[i1] * frac
        return (out.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private fun applyLut3D(pixels: IntArray, lut: LutData.Lut3D) {
        val size = lut.size
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val rf = ((p shr 16) and 0xFF) / 255f
            val gf = ((p shr 8) and 0xFF) / 255f
            val bf = (p and 0xFF) / 255f

            val (r, g, b) = trilinearSample(lut, size, rf, gf, bf)
            pixels[i] = (a shl 24) or
                (r.coerceIn(0f, 1f) * 255f).roundToInt().shl(16) or
                (g.coerceIn(0f, 1f) * 255f).roundToInt().shl(8) or
                (b.coerceIn(0f, 1f) * 255f).roundToInt()
        }
    }

    private fun trilinearSample(lut: LutData.Lut3D, size: Int, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val maxIndex = (size - 1).toFloat()
        val rf = (r * maxIndex).coerceIn(0f, maxIndex)
        val gf = (g * maxIndex).coerceIn(0f, maxIndex)
        val bf = (b * maxIndex).coerceIn(0f, maxIndex)

        val r0 = floor(rf).toInt(); val r1 = (r0 + 1).coerceAtMost(size - 1); val rt = rf - r0
        val g0 = floor(gf).toInt(); val g1 = (g0 + 1).coerceAtMost(size - 1); val gt = gf - g0
        val b0 = floor(bf).toInt(); val b1 = (b0 + 1).coerceAtMost(size - 1); val bt = bf - b0

        fun sample(ri: Int, gi: Int, bi: Int): FloatArray {
            val idx = (bi * size * size + gi * size + ri) * 3
            return floatArrayOf(lut.data[idx], lut.data[idx + 1], lut.data[idx + 2])
        }

        fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] * (1 - t) + b[it] * t }

        val c000 = sample(r0, g0, b0); val c100 = sample(r1, g0, b0)
        val c010 = sample(r0, g1, b0); val c110 = sample(r1, g1, b0)
        val c001 = sample(r0, g0, b1); val c101 = sample(r1, g0, b1)
        val c011 = sample(r0, g1, b1); val c111 = sample(r1, g1, b1)

        val c00 = lerp(c000, c100, rt); val c10 = lerp(c010, c110, rt)
        val c01 = lerp(c001, c101, rt); val c11 = lerp(c011, c111, rt)
        val c0 = lerp(c00, c10, gt); val c1 = lerp(c01, c11, gt)
        val c = lerp(c0, c1, bt)

        return Triple(c[0], c[1], c[2])
    }
}
