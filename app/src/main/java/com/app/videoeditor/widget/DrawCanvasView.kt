package com.app.videoeditor.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class BrushType { PEN, MARKER, HIGHLIGHTER }

data class DrawStroke(
    val points: MutableList<Float> = mutableListOf(), // flattened [fx0, fy0, fx1, fy1, ...] -- 0..1 fractions
    var color: Int,
    var widthFraction: Float, // stroke width, canvas ke min(width,height) ka fraction
    var brush: BrushType,
    var isEraser: Boolean
)

/**
 * Video ke upar freehand drawing -- strokes FIXED hain (draggable/pinchable
 * nahi, jo draw ho gaya wo permanent hai). Coordinates fraction-based (0..1)
 * store hote hain, isliye preview aur export dono me exact match karta hai.
 *
 * isDrawingEnabled=false hone par touch events consume nahi karti (return
 * false), isliye neeche ke stickers/text normally interact karte rehte hain.
 */
class DrawCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentColor: Int = Color.RED
    var currentBrush: BrushType = BrushType.PEN
    var currentWidthFraction: Float = 0.015f
    var isEraserMode: Boolean = false
    var isDrawingEnabled: Boolean = false

    var onStrokeFinished: (() -> Unit)? = null

    private val strokes = mutableListOf<DrawStroke>()
    private var activeStroke: DrawStroke? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun getStrokes(): List<DrawStroke> = strokes.toList()

    fun setStrokes(newStrokes: List<DrawStroke>) {
        strokes.clear()
        strokes.addAll(newStrokes.map { it.copy(points = it.points.toMutableList()) })
        invalidate()
    }

    fun undoLastStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
            onStrokeFinished?.invoke()
        }
    }

    fun clearAll() {
        strokes.clear()
        invalidate()
        onStrokeFinished?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled || width <= 0 || height <= 0) return false
        val fx = event.x / width
        val fy = event.y / height
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeStroke = DrawStroke(
                    color = currentColor,
                    widthFraction = currentWidthFraction,
                    brush = currentBrush,
                    isEraser = isEraserMode
                ).also { it.points.add(fx); it.points.add(fy) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                activeStroke?.points?.apply { add(fx); add(fy) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeStroke?.let { s ->
                    if (s.points.size < 4) {
                        // Single tap -- chhota sa dot-stroke bana do taaki kuch dikhe
                        s.points.add(s.points[0]); s.points.add(s.points[1] + 0.0001f)
                    }
                    strokes.add(s)
                }
                activeStroke = null
                invalidate()
                onStrokeFinished?.invoke()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // FIX: saveLayer zaroori hai -- taaki eraser (PorterDuff.CLEAR) sirf
        // ISI layer ki pehle ki ink clear kare, video ko nahi (jo ek alag
        // View par is layer ke peeche hai).
        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        strokes.forEach { drawStroke(canvas, it) }
        activeStroke?.let { drawStroke(canvas, it) }
        canvas.restoreToCount(layerId)
    }

    private fun drawStroke(canvas: Canvas, stroke: DrawStroke) {
        if (stroke.points.size < 4) return
        val path = Path()
        path.moveTo(stroke.points[0] * width, stroke.points[1] * height)
        var i = 2
        while (i < stroke.points.size) {
            path.lineTo(stroke.points[i] * width, stroke.points[i + 1] * height)
            i += 2
        }

        val minDim = minOf(width, height)
        paint.strokeWidth = stroke.widthFraction * minDim
        paint.strokeCap = if (stroke.brush == BrushType.HIGHLIGHTER) Paint.Cap.BUTT else Paint.Cap.ROUND

        if (stroke.isEraser) {
            paint.color = Color.TRANSPARENT
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            paint.xfermode = null
            paint.color = when (stroke.brush) {
                BrushType.PEN -> stroke.color
                BrushType.MARKER -> withAlpha(stroke.color, 200)
                BrushType.HIGHLIGHTER -> withAlpha(stroke.color, 90)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}