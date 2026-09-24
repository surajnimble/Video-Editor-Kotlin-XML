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
import android.view.View.MeasureSpec
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Self-contained draggable / pinch-zoom / rotate MULTI-LINE text overlay.
 * View hamesha poore parent jitni size leti hai; drawn content Matrix se
 * move/scale/rotate hota hai onDraw() ke andar. Touch hit-testing inverse
 * Matrix se hoti hai. Drawing View ki apni (=video canvas) bounds tak clip
 * hoti hai, taaki zoom/rotate video ke bahar visually overflow na ho.
 *
 * Har line ka apna rounded background "pill" hota hai (textBackgroundColor),
 * aur textAlignment (LEFT/CENTER/RIGHT) se lines ek doosre ke against
 * horizontally align hoti hain -- bilkul dialog ke preview jaisa hi.
 */
class DraggableTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }

    var displayText: String = "Text"
        set(value) { field = value; invalidate() }

    var textColor: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; invalidate() }

    var textBackgroundColor: Int = 0xCC000000.toInt()
        set(value) { field = value; invalidate() }

    var fractionCenterX: Float = 0.5f
        set(value) { field = value.coerceIn(-1f, 2f); invalidate() }
    var fractionCenterY: Float = 0.5f
        set(value) { field = value.coerceIn(-1f, 2f); invalidate() }

    var fractionSize: Float = 0.15f
        set(value) { field = value.coerceIn(0.01f, 20f); invalidate() }

    var rotationDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    var fontStyleId: String = "classic"
        set(value) { field = value; invalidate() }

    // NOTE: static visual variation hi hai -- TextEffects.kt ka comment dekho.
    var textEffectId: String = "none"
        set(value) { field = value; invalidate() }

    /*var textAlignment: Int = ALIGN_CENTER
        set(value) { field = value; invalidate() }*/
    var contentAlignment: Int = ALIGN_CENTER
        set(value) { field = value; invalidate() }

    var isItemActive: Boolean = false
        set(value) { field = value; invalidate() }

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

    // Multi-line ke liye pre-computed per-line layout
    private var lines: List<String> = listOf("Text")
    private var lineWidths: List<Float> = listOf(0f)
    private var lineHeight = 0f
    private var blockWidth = 0f
    private var blockHeight = 0f

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

        // "Pop" effect -> thoda bada scale (static hint)
        val sizeMultiplier = if (textEffectId == "pop") 1.15f else 1f
        val drawSize = minOf(width, height) * fractionSize * sizeMultiplier
        textPaint.textSize = drawSize
        textPaint.strokeWidth = dp(3f)
        textPaint.typeface = TextStyles.byId(fontStyleId).typeface
        // "Typewriter" effect -> extra letter-spacing (static hint)
        textPaint.letterSpacing = if (textEffectId == "typewriter") 0.12f else 0f

        lines = displayText.split("\n").let { if (it.isEmpty()) listOf("") else it }
        lineWidths = lines.map { textPaint.measureText(it) }
        val fm = textPaint.fontMetrics
        val lineSpacingExtra = dp(4f)
        lineHeight = (fm.descent - fm.ascent) + lineSpacingExtra

        val padding = dp(8f)
        blockWidth = (lineWidths.maxOrNull() ?: 0f) + padding * 2
        blockHeight = lineHeight * lines.size

        localBg = RectF(-blockWidth / 2f, -blockHeight / 2f, blockWidth / 2f, blockHeight / 2f)
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
        val padding = dp(8f)
        val cornerRadius = dp(8f)

        lines.forEachIndexed { index, line ->
            val lineWidth = lineWidths[index]
            val lineCenterY = -blockHeight / 2f + lineHeight * index + lineHeight / 2f

            val xCenter = when (textAlignment) {
                ALIGN_LEFT -> -blockWidth / 2f + padding + lineWidth / 2f
                ALIGN_RIGHT -> blockWidth / 2f - padding - lineWidth / 2f
                else -> 0f
            }
            // "Jump" effect -> alternate lines thoda upar/neeche (static zigzag hint)
            val yCenter = lineCenterY + if (textEffectId == "jump") {
                if (index % 2 == 0) -dp(2f) else dp(2f)
            } else 0f

            val baseline = yCenter - (fm.ascent + fm.descent) / 2f

            if (line.isNotEmpty() && Color.alpha(textBackgroundColor) > 0) {
                backgroundPaint.color = textBackgroundColor
                canvas.drawRoundRect(
                    xCenter - lineWidth / 2f - padding,
                    yCenter - (fm.descent - fm.ascent) / 2f,
                    xCenter + lineWidth / 2f + padding,
                    yCenter + (fm.descent - fm.ascent) / 2f,
                    cornerRadius, cornerRadius, backgroundPaint
                )
            }

            textPaint.color = Color.BLACK
            canvas.drawText(line, xCenter, baseline, textPaint)

            textPaint.color = textColor
            textPaint.style = Paint.Style.FILL
            canvas.drawText(line, xCenter, baseline, textPaint)
            textPaint.style = Paint.Style.FILL_AND_STROKE
        }

        if (isItemActive) {
            selectionPaint.strokeWidth = dp(2f)
            canvas.drawRoundRect(localBg, cornerRadius, cornerRadius, selectionPaint)
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