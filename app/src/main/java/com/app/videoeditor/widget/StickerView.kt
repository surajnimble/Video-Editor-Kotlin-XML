package com.app.videoeditor.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Self-contained draggable / pinch-zoom / rotate emoji sticker overlay.
 * Same design as DraggableTextView: this View always fills its parent
 * completely; only the drawn content moves via a Matrix inside onDraw().
 * Touch hit-testing inverse-transforms the touch point through the same
 * Matrix, so grabbing correctly follows rotation. Drawing is clipped to
 * the View's own (= video canvas) bounds, so zoom/rotate never visually
 * overflows past the video edge, even though the underlying position data
 * can still exceed 0..1 (edge crossing).
 */
class StickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var emoji: String = ""
        set(value) { field = value; invalidate() }

    var fractionCenterX: Float = 0.5f
        set(value) { field = value.coerceIn(-1f, 2f); invalidate() }
    var fractionCenterY: Float = 0.5f
        set(value) { field = value.coerceIn(-1f, 2f); invalidate() }

    var fractionSize: Float = 0.18f
        set(value) { field = value.coerceIn(0.01f, 20f); invalidate() }

    var rotationDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    var isStickerSelected: Boolean = false
        set(value) { field = value; invalidate() }

    var onActionEnded: ((StickerView) -> Unit)? = null
    var onTapped: ((StickerView) -> Unit)? = null

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4A90E2.toInt()
    }

    // Local (unrotated, untranslated) bounds of the drawn glyph, centered on (0,0).
    private var localBounds = RectF()
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private var isDragging = false
    private var isPinching = false
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var startFractionX = 0f
    private var startFractionY = 0f
    private var startPinchDistance = 0f
    private var startPinchAngle = 0f
    private var startFractionSize = 0f
    private var startRotation = 0f

    private var primaryPointerId = MotionEvent.INVALID_POINTER_ID
    private var secondaryPointerId = MotionEvent.INVALID_POINTER_ID

    // Hamesha poore parent jitna size -- yehi is design ka core hai.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return

        val drawSize = minOf(width, height) * fractionSize
        emojiPaint.textSize = drawSize

        val glyphWidth = emojiPaint.measureText(emoji)
        val fm = emojiPaint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) / 2f

        // Tight bounds -- exactly jitna glyph visually occupy karta hai,
        // koi extra invisible touch-margin nahi (glyph ke bahar tap karne
        // par sticker select nahi hoga).
        localBounds = RectF(
            -glyphWidth / 2f,
            baselineOffset + fm.ascent,
            glyphWidth / 2f,
            baselineOffset + fm.descent
        )

        drawMatrix.reset()
        drawMatrix.postRotate(rotationDegrees)
        drawMatrix.postTranslate(fractionCenterX * width, fractionCenterY * height)
        drawMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        rebuildGeometry()

        canvas.save()
        // Video-canvas boundary ke bahar visually overflow na ho, isliye
        // drawing ko View ke apne (0,0,width,height) tak hi clip karte hain.
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.concat(drawMatrix)

        val fm = emojiPaint.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2f
        canvas.drawText(emoji, 0f, baseline, emojiPaint)

        if (isStickerSelected) {
            selectionPaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(localBounds, dp(4f), dp(4f), selectionPaint)
        }

        canvas.restore()
    }

    private fun hitTest(x: Float, y: Float): Boolean {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return localBounds.contains(pts[0], pts[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitTest(event.x, event.y)) return false

                isStickerSelected = true
                onTapped?.invoke(this)
                isDragging = true
                isPinching = false
                primaryPointerId = event.getPointerId(0)
                secondaryPointerId = MotionEvent.INVALID_POINTER_ID

                startTouchX = event.x
                startTouchY = event.y
                startFractionX = fractionCenterX
                startFractionY = fractionCenterY
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isDragging || isPinching) return true

                val newIndex = event.actionIndex
                val newX = event.getX(newIndex)
                val newY = event.getY(newIndex)

                val primaryIndex = event.findPointerIndex(primaryPointerId)
                if (primaryIndex == -1) return true

                // Dusri finger sticker ke upar hona zaroori nahi -- pehli
                // finger sticker ko pakde hue hai, dusri kahin bhi ho pinch
                // kaam karega.
                secondaryPointerId = event.getPointerId(newIndex)
                isPinching = true

                val px = event.getX(primaryIndex)
                val py = event.getY(primaryIndex)
                startPinchDistance = hypot(px - newX, py - newY)
                startPinchAngle = atan2(py - newY, px - newX)
                startFractionSize = fractionSize
                startRotation = rotationDegrees

                startTouchX = (px + newX) / 2f
                startTouchY = (py + newY) / 2f
                startFractionX = fractionCenterX
                startFractionY = fractionCenterY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val primaryIndex = event.findPointerIndex(primaryPointerId)
                if (primaryIndex == -1) return true

                if (isPinching) {
                    val secondaryIndex = event.findPointerIndex(secondaryPointerId)
                    if (secondaryIndex == -1) {
                        isPinching = false
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        startTouchX = event.getX(primaryIndex)
                        startTouchY = event.getY(primaryIndex)
                        startFractionX = fractionCenterX
                        startFractionY = fractionCenterY
                    } else {
                        val px = event.getX(primaryIndex); val py = event.getY(primaryIndex)
                        val sx = event.getX(secondaryIndex); val sy = event.getY(secondaryIndex)

                        val curMidX = (px + sx) / 2f
                        val curMidY = (py + sy) / 2f
                        fractionCenterX = startFractionX + (curMidX - startTouchX) / width
                        fractionCenterY = startFractionY + (curMidY - startTouchY) / height

                        val dist = hypot(px - sx, py - sy)
                        fractionSize = startFractionSize * (dist / startPinchDistance)

                        val angleDiff = atan2(py - sy, px - sx) - startPinchAngle
                        rotationDegrees = startRotation + Math.toDegrees(angleDiff.toDouble()).toFloat()

                        invalidate()
                        return true
                    }
                }

                val curX = event.getX(primaryIndex)
                val curY = event.getY(primaryIndex)
                fractionCenterX = startFractionX + (curX - startTouchX) / width
                fractionCenterY = startFractionY + (curY - startTouchY) / height
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                when {
                    isPinching && liftedId == secondaryPointerId -> {
                        isPinching = false
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        val primaryIndex = event.findPointerIndex(primaryPointerId)
                        if (primaryIndex != -1) {
                            startTouchX = event.getX(primaryIndex)
                            startTouchY = event.getY(primaryIndex)
                            startFractionX = fractionCenterX
                            startFractionY = fractionCenterY
                        }
                    }
                    liftedId == primaryPointerId && secondaryPointerId != MotionEvent.INVALID_POINTER_ID -> {
                        // Primary uthhi lekin dusri abhi neeche hai -> usi ko
                        // naya primary banao, gesture seamlessly continue.
                        val promotedIndex = event.findPointerIndex(secondaryPointerId)
                        primaryPointerId = secondaryPointerId
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        isPinching = false
                        if (promotedIndex != -1) {
                            startTouchX = event.getX(promotedIndex)
                            startTouchY = event.getY(promotedIndex)
                            startFractionX = fractionCenterX
                            startFractionY = fractionCenterY
                        }
                    }
                    liftedId == primaryPointerId -> {
                        isDragging = false
                        isPinching = false
                        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                        performClick()
                        onActionEnded?.invoke(this)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging || isPinching) {
                    isDragging = false
                    isPinching = false
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                    performClick()
                    onActionEnded?.invoke(this)
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun dp(v: Float): Float = (v * resources.displayMetrics.density).coerceAtLeast(0f)
}