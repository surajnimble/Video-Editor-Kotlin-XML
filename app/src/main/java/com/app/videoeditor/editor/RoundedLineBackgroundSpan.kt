package com.app.videoeditor.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.LineBackgroundSpan

/**
 * Har line ke peeche rounded pill background. `alignment` set karke box ko
 * bhi text ke saath (LEFT/CENTER/RIGHT) shift kiya ja sakta hai.
 */
class RoundedLineBackgroundSpan(
    var backgroundColor: Int,
    private val cornerRadius: Float,
    private val horizontalPadding: Float,
    private val verticalPadding: Float,
    var alignment: Int = 1 // 0=LEFT, 1=CENTER, 2=RIGHT -- DraggableTextView.ALIGN_* se match
) : LineBackgroundSpan {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        if (backgroundColor == Color.TRANSPARENT || start >= end) return

        val lineText = text.subSequence(start, end).toString()
        // FIX: khaali/sirf-whitespace line ke liye background skip -- pehle
        // sirf "start == end" check tha, lekin Android kabhi-kabhi ek khaali
        // line ko bhi 1-length range deta hai (jaise sirf "\n"), isliye woh
        // check miss ho jaata tha aur ek patli "stalk" jaisi background
        // dikhti thi paragraphs ke beech.
        if (lineText.isBlank()) return

        val textWidth = paint.measureText(lineText)
        // FIX: box bhi alignment follow kare -- pehle hamesha (left+right)/2
        // par center hota tha, chahe text RIGHT/LEFT-aligned ho.
        val centerX = when (alignment) {
            0 -> left + textWidth / 2f
            2 -> right - textWidth / 2f
            else -> (left + right) / 2f
        }

        // FIX: Android LineBackgroundSpan ko hamesha left=0, right=layoutWidth
        // deta hai. Rect ko [left, right] par clamp karne se ek taraf padding
        // zero ho jaati thi (text edge se chipak jaata tha). Ab EditText par
        // transparent setShadowLayer se clip [left - horizontalPadding,
        // right + horizontalPadding] tak expand ho chuka hai (EditorActivity
        // me showAddTextDialog dekho), isliye dono taraf equal padding ke saath
        // draw kar sakte hain -- clip hi ise visible rakhta hai.
        val expand = horizontalPadding
        val bgLeft = (centerX - textWidth / 2f - horizontalPadding).coerceAtLeast(left - expand)
        val bgRight = (centerX + textWidth / 2f + horizontalPadding).coerceAtMost(right + expand)

        fillPaint.color = backgroundColor
        rect.set(
            bgLeft,
            top - verticalPadding,
            bgRight,
            bottom + verticalPadding
        )
        if (rect.width() > 0f && rect.height() > 0f) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        }
    }
}