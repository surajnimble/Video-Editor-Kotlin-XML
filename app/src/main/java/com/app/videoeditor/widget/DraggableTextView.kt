package com.app.videoeditor.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

class DraggableTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var displayText: String = "Text"
        set(value) {
            field = value; invalidate()
        }

    var textColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value; invalidate()
        }

    var textBackgroundColor: Int = 0xCC000000.toInt()
        set(value) {
            field = value; invalidate()
        }

    var fractionCenterX: Float = 0.5f
        set(value) {
            field = value.coerceIn(-1f, 2f); invalidate()
        }
    var fractionCenterY: Float = 0.5f
        set(value) {
            field = value.coerceIn(-1f, 2f); invalidate()
        }

    var fractionSize: Float = 0.15f
        set(value) {
            field = value.coerceIn(0.01f, 20f); invalidate()
        }

    var rotationDegrees: Float = 0f
        set(value) {
            field = value; invalidate()
        }

    var isItemActive: Boolean = false
        set(value) {
            field = value; invalidate()
        }

    var onActionEnded: ((DraggableTextView) -> Unit)? = null
    var onTapped: ((DraggableTextView) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL_AND_STROKE
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4A90E2.toInt()
    }

    private var localBg = RectF()
    private var localTouch = RectF()
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
        textPaint.textSize = drawSize
        textPaint.strokeWidth = dp(3f)

        val textWidth = textPaint.measureText(displayText)
        val fm = textPaint.fontMetrics
        val padding = dp(8f)
        val baselineOffset = -(fm.ascent + fm.descent) / 2f

        localBg = RectF(
            -textWidth / 2f - padding,
            baselineOffset + fm.ascent - padding,
            textWidth / 2f + padding,
            baselineOffset + fm.descent + padding
        )
        localTouch = RectF(localBg)

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
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.concat(drawMatrix)

        val fm = textPaint.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2f

        if (Color.alpha(textBackgroundColor) > 0) {
            backgroundPaint.color = textBackgroundColor
            canvas.drawRoundRect(localBg, dp(8f), dp(8f), backgroundPaint)
        }

        textPaint.color = Color.BLACK
        canvas.drawText(displayText, 0f, baseline, textPaint)

        textPaint.color = textColor
        textPaint.style = Paint.Style.FILL
        canvas.drawText(displayText, 0f, baseline, textPaint)
        textPaint.style = Paint.Style.FILL_AND_STROKE

        if (isItemActive) {
            selectionPaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(localBg, dp(8f), dp(8f), selectionPaint)
        }

        canvas.restore()
    }

    private fun hitTest(x: Float, y: Float): Boolean {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return localTouch.contains(pts[0], pts[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitTest(event.x, event.y)) return false

                isItemActive = true
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
                        val px = event.getX(primaryIndex);
                        val py = event.getY(primaryIndex)
                        val sx = event.getX(secondaryIndex);
                        val sy = event.getY(secondaryIndex)

                        val curMidX = (px + sx) / 2f
                        val curMidY = (py + sy) / 2f
                        fractionCenterX = startFractionX + (curMidX - startTouchX) / width
                        fractionCenterY = startFractionY + (curMidY - startTouchY) / height

                        val dist = hypot(px - sx, py - sy)
                        fractionSize = startFractionSize * (dist / startPinchDistance)

                        val angleDiff = atan2(py - sy, px - sx) - startPinchAngle
                        rotationDegrees =
                            startRotation + Math.toDegrees(angleDiff.toDouble()).toFloat()

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

    override fun performClick(): Boolean {
        super.performClick(); return true
    }

    private fun dp(v: Float): Float = (v * resources.displayMetrics.density).coerceAtLeast(0f)
}