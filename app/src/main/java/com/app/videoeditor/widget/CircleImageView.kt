package com.app.videoeditor.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** Plain ImageView clipped to a circle, with an optional colored ring - used for filter/story-style thumbnails. */
class CircleImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val clipPath = Path()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    var ringColor: Int = 0
        set(value) { field = value; invalidate() }
    var ringWidthPx: Float = 0f
        set(value) { field = value; onSizeChanged(width, height, width, height); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipPath.reset()
        val radius = min(w, h) / 2f
        clipPath.addCircle(w / 2f, h / 2f, (radius - ringWidthPx).coerceAtLeast(0f), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(save)

        if (ringWidthPx > 0f && ringColor != 0) {
            ringPaint.color = ringColor
            ringPaint.strokeWidth = ringWidthPx
            val radius = (min(width, height) / 2f) - ringWidthPx / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius, ringPaint)
        }
    }
}
