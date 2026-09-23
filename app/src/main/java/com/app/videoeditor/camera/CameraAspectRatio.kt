package com.app.videoeditor.camera

import android.util.Rational

/**
 * Instagram-style capture ratios. "16:9" in the UI is the familiar full-screen portrait
 * shape, which numerically is a 9:16 Rational (taller than wide) - that's intentional,
 * it matches what users expect the label to mean, not a typo.
 */
enum class CameraAspectRatio(val label: String, val rational: Rational) {
    SQUARE("1:1", Rational(1, 1)),
    PORTRAIT_4_5("4:5", Rational(4, 5)),
    FULL_9_16("16:9", Rational(9, 16));

    companion object {
        fun default() = FULL_9_16
    }
}
